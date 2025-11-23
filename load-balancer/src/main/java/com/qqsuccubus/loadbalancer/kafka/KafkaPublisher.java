package com.qqsuccubus.loadbalancer.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qqsuccubus.core.msg.ControlMessages;
import com.qqsuccubus.core.msg.Topics;
import com.qqsuccubus.loadbalancer.config.LBConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;
import reactor.kafka.sender.SenderRecord;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka publisher for control messages (ring updates, scale signals).
 */
public class KafkaPublisher implements IRingPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaPublisher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final KafkaSender<String, String> sender;

    public KafkaPublisher(LBConfig config) {
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrap());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        SenderOptions<String, String> senderOptions = SenderOptions.create(producerProps);
        this.sender = KafkaSender.create(senderOptions);

        log.info("Kafka publisher initialized");
    }

    /**
     * Publishes a ring update to the control topic.
     *
     * @param ringUpdate Ring update message
     * @return Mono completing when published
     */
    public Mono<Void> publishRingUpdate(ControlMessages.RingUpdate ringUpdate) {
        try {
            String json = MAPPER.writeValueAsString(ringUpdate);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    Topics.CONTROL_RING,
                    null, // no key (broadcast)
                    json
            );

            return sender.send(Mono.just(SenderRecord.create(record, null)))
                    .next()
                    .doOnSuccess(result -> log.info("Published ring update: version={}",
                            ringUpdate.getVersion().getVersion()))
                    .doOnError(err -> log.error("Failed to publish ring update", err))
                    .then();
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    /**
     * Publishes a scale signal to the control topic.
     *
     * @param scaleSignal Scale signal message
     * @return Mono completing when published
     */
    public Mono<Void> publishScaleSignal(ControlMessages.ScaleSignal scaleSignal) {
        try {
            String json = MAPPER.writeValueAsString(scaleSignal);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    Topics.CONTROL_SCALE,
                    null, // no key (broadcast)
                    json
            );

            return sender.send(Mono.just(SenderRecord.create(record, null)))
                    .next()
                    .doOnSuccess(result -> log.info("Published scale signal: action={}",
                            scaleSignal.getDirective().getAction()))
                    .doOnError(err -> log.error("Failed to publish scale signal", err))
                    .then();
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    /**
     * Publishes a drain signal to the control topic.
     *
     * @param drainSignal Drain signal message
     * @return Mono completing when published
     */
    public Mono<Void> publishDrainSignal(ControlMessages.DrainSignal drainSignal) {
        try {
            String json = MAPPER.writeValueAsString(drainSignal);
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
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    /**
     * Closes the Kafka producer.
     */
    public void close() {
        sender.close();
        log.info("Kafka publisher closed");
    }
}

