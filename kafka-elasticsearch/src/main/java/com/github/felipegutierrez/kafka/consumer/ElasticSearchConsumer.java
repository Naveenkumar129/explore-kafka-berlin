package com.github.felipegutierrez.kafka.consumer;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;

public class ElasticSearchConsumer {
    private static final Logger logger = LoggerFactory.getLogger(ElasticSearchConsumer.class);
    private final String elasticSearchCredentialFile = "elasticsearch.token";
    private final String bootstrapServers = "127.0.0.1:9092";
    private final String groupId = "kafka-demo-elasticsearch";
    private final String topic = "twitter_tweets";
    private final int maxInsert = 5;
    private boolean insertIntoElasticsearch = false;
    private String hostname;
    private String username;
    private String password;

    public ElasticSearchConsumer() {
        try {
            disclaimer();
            leadCredentials();
            RestHighLevelClient client = createClient();

            KafkaConsumer<String, String> consumer = createConsumer();
            int count = 0;
            // poll for new data
            while (insertIntoElasticsearch) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

                for (ConsumerRecord<String, String> record : records) {
                    // logger.info("Key:" + record.key() + " Value:" + record.value() + " Partition:" + record.partition() + " Offset:" + record.offset());
                    // insert data into elasticsearch
                    String jsonString = record.value(); // "{\"foo\": \"bar\"}";

                    // make sure that the index id exist at https://app.bonsai.io/clusters/kafka-5082250343/console
                    IndexRequest indexResquest = new IndexRequest("twitter", "tweets").source(jsonString, XContentType.JSON);

                    IndexResponse indexResponse = client.index(indexResquest, RequestOptions.DEFAULT);
                    String id = indexResponse.getId();
                    logger.info("Go to the Elasticsearch https://app.bonsai.io/clusters/kafka-5082250343/console and search for: ");
                    logger.info("GET: /twitter/tweets/" + id);
                    count++;
                    if (count >= maxInsert) {
                        insertIntoElasticsearch = false;
                        break;
                    }
                    Thread.sleep(1000); // introduce a small delay
                }
            }

            // close the elasticsearch client
            client.close();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void leadCredentials() {
        logger.info("Loading ElasticSearch bonzai.io credentials from [" + elasticSearchCredentialFile + "]");
        InputStream in = getClass().getClassLoader().getResourceAsStream(elasticSearchCredentialFile);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            int count = 0;
            while (reader.ready()) {
                count++;
                String line = reader.readLine();
                String[] tokens = line.split("@");
                if (tokens.length == 2) {
                    String[] userPass = tokens[0].split(":");
                    if (userPass.length == 3) {
                        this.username = userPass[1].replace("//", "");
                        this.password = userPass[2];
                    }
                    String[] host = tokens[1].split(":");
                    if (host.length == 2) {
                        this.hostname = host[0];
                    }
                } else {
                    throw new IOException();
                }
            }
            insertIntoElasticsearch = true;
            logger.info("Tokens read. username: " + username + ", password: *********, hostname: " + hostname);
        } catch (NullPointerException | FileNotFoundException e) {
            logger.error("File [" + elasticSearchCredentialFile + "] not found.");
        } catch (IOException e) {
            logger.error("File [" + elasticSearchCredentialFile + "] has wrong format. Please use: https://USERNAME:PASSWORD@HOSTNAME:443");
        }
    }

    private RestHighLevelClient createClient() {
        // do not execute this if you are running a local ElasticSearch
        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
        RestClientBuilder builder = RestClient
                .builder(new HttpHost(hostname, 443, "https"))
                .setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                    @Override
                    public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpAsyncClientBuilder) {
                        return httpAsyncClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    }
                });
        RestHighLevelClient client = new RestHighLevelClient(builder);
        return client;
    }

    private KafkaConsumer<String, String> createConsumer() {
        // create properties
        Properties properties = new Properties();
        properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // create consumer
        KafkaConsumer<String, String> consumer = new KafkaConsumer<String, String>(properties);

        // subscribe consumer to our topic(s)
        consumer.subscribe(Arrays.asList(topic));

        return consumer;
    }

    private void disclaimer() {
        logger.info("Start zookeeper: ./bin/zookeeper-server-start.sh config/zookeeper.properties");
        logger.info("Start the broker: ./bin/kafka-server-start.sh config/server.properties");
        logger.info("remove the topic: ./bin/kafka-topics.sh --delete --topic twitter_tweets --zookeeper localhost:2181");
        logger.info("create the topic: ./bin/kafka-topics.sh --create --topic twitter_tweets --zookeeper localhost:2181 --partitions 6 --replication-factor 1");
        logger.info("Start the consumer: java -jar kafka-twitter/target/kafka-twitter-1.0.jar -app 1 -elements \"felipe\"");
        logger.info("");
        logger.info("");
        logger.info("");
        logger.info("");
    }
}
