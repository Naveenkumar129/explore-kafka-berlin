package com.github.felipegutierrez.kafka.producers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.twitter.hbc.ClientBuilder;
import com.twitter.hbc.core.Client;
import com.twitter.hbc.core.Constants;
import com.twitter.hbc.core.Hosts;
import com.twitter.hbc.core.HttpHosts;
import com.twitter.hbc.core.endpoint.StatusesFilterEndpoint;
import com.twitter.hbc.core.processor.StringDelimitedProcessor;
import com.twitter.hbc.httpclient.auth.Authentication;
import com.twitter.hbc.httpclient.auth.OAuth1;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Create the Kafka topic:
 * # ./bin/kafka-topics.sh --create --topic twitter-topic --zookeeper localhost:2181 --partitions 6 --replication-factor 1
 * <p>
 * Consuming from Kafka:
 * # ./bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic twitter-topic --property print.key=true
 * <p>
 * Start this application:
 * # java -jar target/explore-kafka-1.0.jar -app 7 -elements "felipe|simone"
 */
public class TwitterProducer {
    private final Logger logger = LoggerFactory.getLogger(TwitterProducer.class);
    private final String twitterAccessTokenFile = "twitter.access.token";
    private final String bootstrapServers = "127.0.0.1:9092";
    private final List<String> terms;
    private final ObjectMapper jsonParser;
    private String consumerKey;
    private String consumerSecret;
    private String token;
    private String secret;

    public TwitterProducer() {
        this("corona");
    }

    public TwitterProducer(String elements) {
        if (Strings.isNullOrEmpty(elements)) {
            // new RuntimeException("the -elements parameter cannot be empty");
            this.terms = Lists.newArrayList("corona");
        } else {
            String[] values = elements.toLowerCase().split(Pattern.quote("|"));
            this.terms = Lists.newArrayList(values);
        }
        this.jsonParser = new ObjectMapper();
        loadTokens();
    }

    public void run() {
        /** Set up your blocking queues: Be sure to size these properly based on expected TPS of your stream */
        BlockingQueue<String> msgQueue = new LinkedBlockingQueue<String>(1000);

        // create a twitter client
        Client hosebirdClient = createTwitterClient(msgQueue);// Attempts to establish a connection.
        hosebirdClient.connect();

        // create a kafka producer
        KafkaProducer<String, String> producer = createKafkaProducer();

        // shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Stopping application with the shutdown hook");
            logger.info("shutting down Twitter client");
            hosebirdClient.stop();
            logger.info("shutting down Kafka producer and sending all messages in-memory");
            producer.close();
            logger.info("done!");
        }));

        // loop and send tweets to kafka
        // on a different thread, or multiple different threads....
        while (!hosebirdClient.isDone()) {
            String msg = null;
            try {
                msg = msgQueue.poll(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.error("Error: ", e.getMessage());
                hosebirdClient.stop();
            }
            if (msg != null) {
                // logger.info("message: " + msg);
                // extract key
                String key = null;
                for (String k : this.terms) {
                    if (msg.toLowerCase().contains(k)) {
                        key = k;
                    }
                }
                // extract message
                String text = extractMessage(msg);
                final ProducerRecord<String, String> record = new ProducerRecord<String, String>("twitter-topic", key, text);
                producer.send(record, new Callback() {
                    @Override
                    public void onCompletion(RecordMetadata recordMetadata, Exception e) {
                        if (e == null) {
                            // the record was successfully sent
                            logger.info("message was sent successfully: " + text);
                        } else {
                            logger.error("Error on sending message: " + e.getMessage());
                        }
                    }
                });
            }
        }
        logger.info("End of application");
    }

    private KafkaProducer<String, String> createKafkaProducer() {
        // create properties
        Properties properties = new Properties();
        properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.TRUE.toString());
        KafkaProducer<String, String> producer = new KafkaProducer<String, String>(properties);

        return producer;
    }

    private String extractMessage(String msg) {
        try {
            JsonNode jsonNode = jsonParser.readValue(msg, JsonNode.class);
            boolean hasText = jsonNode.has("text");
            if (hasText) {
                // message of tweet
                String text = jsonNode.get("text").asText();
                return text;
            }
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return "";
    }

    private void consume() {

    }

    private void loadTokens() {
        InputStream in = getClass().getClassLoader().getResourceAsStream(twitterAccessTokenFile);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            int count = 0;
            while (reader.ready()) {
                count++;
                String line = reader.readLine();
                String[] tokens = line.split(":");
                if (tokens.length == 2) {
                    if (count == 1) consumerKey = tokens[1];
                    if (count == 2) consumerSecret = tokens[1];
                    if (count == 3) token = tokens[1];
                    if (count == 4) secret = tokens[1];
                } else {
                    throw new IOException();
                }
            }
            logger.info("Tokens read. consumerKey: " + consumerKey + ", consumerSecret: " + consumerSecret +
                    ", token: " + token + ", secret: " + secret);
        } catch (NullPointerException | FileNotFoundException e) {
            logger.error("File [" + twitterAccessTokenFile + "] not found.");
        } catch (IOException e) {
            logger.error("File [" + twitterAccessTokenFile + "] has wrong format. Please use: \n" +
                    "API key:xxxxxxxxxxxxxxxxxxx\n" +
                    "API secret key:xxxxxxxxxxxxxxxxxxx\n" +
                    "Access token:xxxxxxxxxxxxxxxxxxx\n" +
                    "Access token secret:xxxxxxxxxxxxxxxxxxx");
        }
    }

    private Client createTwitterClient(BlockingQueue<String> msgQueue) {
        /** Declare the host you want to connect to, the endpoint, and authentication (basic auth or oauth) */
        Hosts hosebirdHosts = new HttpHosts(Constants.STREAM_HOST);
        StatusesFilterEndpoint hosebirdEndpoint = new StatusesFilterEndpoint();
        hosebirdEndpoint.trackTerms(terms);

        // These secrets should be read from a config file
        Authentication hosebirdAuth = new OAuth1(this.consumerKey, this.consumerSecret, this.token, this.secret);

        ClientBuilder builder = new ClientBuilder()
                .name("Hosebird-Client-Twitter-01") // optional: mainly for the logs
                .hosts(hosebirdHosts)
                .authentication(hosebirdAuth)
                .endpoint(hosebirdEndpoint)
                .processor(new StringDelimitedProcessor(msgQueue));
        return builder.build();
    }
}
