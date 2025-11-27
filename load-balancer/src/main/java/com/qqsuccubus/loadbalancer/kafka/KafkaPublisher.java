package com.qqsuccubus.loadbalancer.kafka;

import com.qqsuccubus.core.msg.ControlMessages;
import com.qqsuccubus.core.msg.Topics;
import com.qqsuccubus.core.util.JsonUtils;
import com.qqsuccubus.loadbalancer.config.LBConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;
import reactor.kafka.sender.SenderRecord;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class KafkaPublisher implements IRingPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaPublisher.class);

    private final KafkaSender<String, String> sender;
    private final AdminClient adminClient;

    public KafkaPublisher(LBConfig config) {
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrap());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        SenderOptions<String, String> senderOptions = SenderOptions.create(producerProps);
        this.sender = KafkaSender.create(senderOptions);

        // Setup admin client for topic management
        Map<String, Object> adminProps = new HashMap<>();
        adminProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrap());
        this.adminClient = AdminClient.create(adminProps);

        createTopicIfNotExists(Topics.CONTROL_RING, 4, (short) 1)
            .then(createTopicIfNotExists(Topics.CONTROL_SCALE, 1, (short) 1))
            .subscribe();
        log.info("Kafka publisher initialized");
    }

    public Mono<Void> publishRingUpdate(ControlMessages.RingUpdate ringUpdate) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
            Topics.CONTROL_RING,
            null, // no key (broadcast)
            JsonUtils.writeValueAsString(ringUpdate)
        );

        return sender.send(Mono.just(SenderRecord.create(record, null)))
            .next()
            .doOnSuccess(result -> log.info("Published ring update: version={}",
                ringUpdate.getVersion().getVersion()))
            .doOnError(err -> log.error("Failed to publish ring update", err))
            .then();
    }

    public Mono<Void> publishScaleSignal(ControlMessages.ScaleSignal scaleSignal) {
        String json = JsonUtils.writeValueAsString(scaleSignal);
        ProducerRecord<String, String> record = new ProducerRecord<>(
            Topics.CONTROL_SCALE,
            null, // no key (broadcast)
            json
        );

        return sender.send(Mono.just(SenderRecord.create(record, null)))
            .next()
            .doOnError(err -> log.error("Failed to publish scale signal", err))
            .then();
    }

    public Mono<Void> publishDrainSignal(ControlMessages.DrainSignal drainSignal) {
        String json = JsonUtils.writeValueAsString(drainSignal);
        ProducerRecord<String, String> record = new ProducerRecord<>(
            Topics.CONTROL_DRAIN,
            drainSignal.getNodeId(), // partition by nodeId
            json
        );

        return sender.send(Mono.just(SenderRecord.create(record, null)))
            .next()
            .doOnSuccess(result -> log.info("Published drain signal: nodeId={}, reason={}",
                drainSignal.getNodeId(), drainSignal.getReason()))
            .doOnError(err -> log.error("Failed to publish drain signal", err))
            .then();
    }

    private Mono<Void> createTopicIfNotExists(String topicName, int partitions, short replicationFactor) {
        return Mono.fromFuture(() -> adminClient.listTopics().names().toCompletionStage().toCompletableFuture())
            .flatMap(names -> {
                if (names.contains(topicName)) {
                    return Mono.empty();
                }

                return Mono.fromFuture(() -> {
                    NewTopic newTopic = new NewTopic(topicName, partitions, replicationFactor);

                    log.info("Creating Kafka topic: {} (partitions={}, replication={})",
                        topicName, partitions, replicationFactor);

                    return adminClient.createTopics(Collections.singleton(newTopic))
                        .all()
                        .toCompletionStage()
                        .toCompletableFuture();
                });
            })
            .doOnSuccess(v -> log.info("Kafka topic created successfully: {}", topicName))
            .onErrorResume(error -> {
                // Check if error is TopicExistsException (topic already exists - this is OK)
                if (error.getCause() instanceof TopicExistsException) {
                    log.info("Kafka topic already exists: {}", topicName);
                    return Mono.empty();
                }

                // Other errors are real problems
                log.error("Failed to create Kafka topic {}: {}", topicName, error.getMessage(), error);
                return Mono.error(error);
            })
            .then();
    }

    /**
     * Closes the Kafka producer.
     */
    public void close() {
        sender.close();
        log.info("Kafka publisher closed");
    }
}

