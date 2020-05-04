/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.azure.cosmos.document;

import com.azure.cosmos.ConnectionPolicy;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosClientException;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;

import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.FeedOptions;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.fasterxml.jackson.databind.JsonNode;

import org.apache.nifi.processor.Processor;
import org.apache.nifi.services.azure.cosmos.document.AzureCosmosDBConnectionControllerService;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.apache.nifi.util.file.FileUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public abstract class ITAbstractAzureCosmosDBDocument {
    static Logger logger = Logger.getLogger(ITAbstractAzureCosmosDBDocument.class.getName());

    private static final Properties CONFIG;

    private static final String CREDENTIALS_FILE = System.getProperty("user.home") + "/azure-credentials.PROPERTIES";
    protected static final String TEST_COSMOS_DB_NAME = "nifi-test-db";
    protected static final String TEST_COSMOS_CONTAINER_NAME = "nifi-test-container";
    protected static final String TEST_COSMOS_PARTITION_KEY_FIELD_NAME = "category";
    protected static CosmosClient client;
    protected static CosmosDatabase cdb;
    protected static CosmosContainer container;

    static {
        final FileInputStream fis;
        CONFIG = new Properties();
        try {
            fis = new FileInputStream(CREDENTIALS_FILE);
            try {
                CONFIG.load(fis);
            } catch (IOException e) {
                fail("Could not open credentials file " + CREDENTIALS_FILE + ": " + e.getLocalizedMessage());
            } finally {
                FileUtils.closeQuietly(fis);
            }
        } catch (FileNotFoundException e) {
            fail("Could not open credentials file " + CREDENTIALS_FILE + ": " + e.getLocalizedMessage());
        }
    }

    protected static String getComosURI() {
        return CONFIG.getProperty("cosmosURI");
    }

    protected static String getCosmosKey() {
        return CONFIG.getProperty("cosmosKey");
    }

    protected TestRunner runner;

    @BeforeClass
    public static void createTestDBContainerIfNeeded() throws CosmosClientException {
        final String testDBURI =  getComosURI();
        final String testDBContainer = getCosmosKey();
        client = new CosmosClientBuilder()
                .endpoint(testDBURI)
                .key(testDBContainer)
                .connectionPolicy(ConnectionPolicy.getDefaultPolicy())
                .buildClient();
        cdb = client.createDatabaseIfNotExists(TEST_COSMOS_DB_NAME).getDatabase();
        CosmosContainerProperties containerProperties =
            new CosmosContainerProperties(TEST_COSMOS_CONTAINER_NAME, "/"+TEST_COSMOS_PARTITION_KEY_FIELD_NAME);
        container = cdb.createContainerIfNotExists(containerProperties, 400).getContainer();
        assertNotNull(container);
    }

    @AfterClass
    public static void dropTestDBAndContainer() throws CosmosClientException {
        resetTestCosmosConnection();
        if(container != null) {
            container.delete();
            container = null;
        }
        if(cdb != null) {
            cdb.delete();
            cdb = null;

        }
        if(client != null){
            client.close();
            client = null;
        }
    }

    @Before
    public void setUpCosmosIT() {
        final String testDBURI =  getComosURI();
        final String testDBContainer = getCosmosKey();
        runner = TestRunners.newTestRunner(getProcessorClass());
        runner.setProperty(AbstractAzureCosmosDBProcessor.URI, testDBURI);
        runner.setProperty(AbstractAzureCosmosDBProcessor.DB_ACCESS_KEY, testDBContainer);
        runner.setProperty(AbstractAzureCosmosDBProcessor.DATABASE_NAME, TEST_COSMOS_DB_NAME);
        runner.setProperty(AbstractAzureCosmosDBProcessor.CONTAINER_ID, TEST_COSMOS_CONTAINER_NAME);
        runner.setProperty(AbstractAzureCosmosDBProcessor.PARTITION_KEY, TEST_COSMOS_PARTITION_KEY_FIELD_NAME);
        runner.setIncomingConnection(false);
        runner.setNonLoopConnection(false);
    }

    protected static void closeClient() {
        client.close();
        client =null;
        cdb = null;
        container = null;
    }

    protected static void resetTestCosmosConnection() {
        if(client != null) {
            closeClient();
        }
        final String testDBURI =  getComosURI();
        final String testDBContainer = getCosmosKey();

        client = new CosmosClientBuilder()
                    .endpoint(testDBURI)
                    .key(testDBContainer)
                    .connectionPolicy(ConnectionPolicy.getDefaultPolicy())
                    .buildClient();
        cdb =  client.getDatabase(TEST_COSMOS_DB_NAME);
        container =  cdb.getContainer(TEST_COSMOS_CONTAINER_NAME);
    }


    protected abstract Class<? extends Processor> getProcessorClass();

    protected void configureCosmosConnectionControllerService() throws Exception {
        runner.removeProperty(AbstractAzureCosmosDBProcessor.URI);
        runner.removeProperty(AbstractAzureCosmosDBProcessor.DB_ACCESS_KEY);

        AzureCosmosDBConnectionControllerService service = new AzureCosmosDBConnectionControllerService();
        runner.addControllerService("connService", service);

        runner.setProperty(service, AzureCosmosDBConnectionControllerService.URI,getComosURI());
        runner.setProperty(service, AzureCosmosDBConnectionControllerService.DB_ACCESS_KEY, getCosmosKey());
        // now, after enabling and setting the service, it should be valid
        runner.enableControllerService(service);
        runner.setProperty(AbstractAzureCosmosDBProcessor.CONNECTION_SERVICE, service.getIdentifier());
        runner.assertValid();
    }

    protected void clearTestData() throws Exception {
        logger.info("clearing test data");
        FeedOptions queryOptions = new FeedOptions();

        CosmosPagedIterable<JsonNode> response = container.queryItems(
            "select * from c order by c._ts", queryOptions, JsonNode.class );

        response.forEach(data ->{
            System.out.println(data);
            if (data.get(TEST_COSMOS_PARTITION_KEY_FIELD_NAME) != null){
                PartitionKey pkey = new PartitionKey(data.get(TEST_COSMOS_PARTITION_KEY_FIELD_NAME).asText());
                container.deleteItem(data.get("id").asText(), pkey, new CosmosItemRequestOptions());
            } else {
                container.deleteItem(data.get("id").asText(), PartitionKey.NONE, new CosmosItemRequestOptions());
            }
        });
    }

}
