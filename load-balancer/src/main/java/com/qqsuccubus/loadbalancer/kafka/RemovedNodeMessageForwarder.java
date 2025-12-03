package com.qqsuccubus.loadbalancer.kafka;

import com.qqsuccubus.core.msg.Envelope;
import com.qqsuccubus.core.msg.Topics;
import com.qqsuccubus.core.util.JsonUtils;
import com.qqsuccubus.loadbalancer.config.LBConfig;
import com.qqsuccubus.loadbalancer.redis.IRedisService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;
import reactor.kafka.sender.SenderRecord;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Service that handles message forwarding from removed socket nodes.
 * <p>
 * When a socket node is removed (scale-in, crash, drain), this service:
 * 1. Subscribes to the removed node's delivery topic (delivery_node_{nodeId})
 * 2. For each message, computes the new target node using consistent hash
 * 3. Forwards the message to the new node's delivery topic
 * 4. Continues for 5 minutes to handle in-flight messages
 * </p>
 * <p>
 * This ensures no messages are lost during node removal.
 * </p>
 */
public class RemovedNodeMessageForwarder {
    private static final Logger log = LoggerFactory.getLogger(RemovedNodeMessageForwarder.class);

    private static final Duration FORWARDING_DURATION = Duration.ofMinutes(5);

    private final LBConfig config;
    private final Function<String, String> nodeResolver; // clientId -> nodeId
    private final KafkaSender<String, String> sender;
    private final IRedisService redisService;

    // Track active forwarders: removedNodeId -> Disposable
    private final Map<String, Disposable> activeForwarders = new ConcurrentHashMap<>();

    public RemovedNodeMessageForwarder(LBConfig config, IRedisService redisService, Function<String, String> nodeResolver) {
        this.config = config;
        this.nodeResolver = nodeResolver;
        this.redisService = redisService;

        // Create shared Kafka sender for forwarding
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrap());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        SenderOptions<String, String> senderOptions = SenderOptions.create(producerProps);
        this.sender = KafkaSender.create(senderOptions);

        log.info("RemovedNodeMessageForwarder initialized");
    }

    /**
     * Starts forwarding messages from removed nodes' topics.
     * <p>
     * For each removed node:
     * 1. Creates a Kafka consumer for that node's delivery topic
     * 2. Forwards messages to the appropriate active node
     * 3. Automatically stops after 5 minutes
     * </p>
     *
     * @param removedNodes Set of removed node IDs
     */
    public void startForwarding(Set<String> removedNodes) {
        if (removedNodes == null || removedNodes.isEmpty()) {
            return;
        }

        for (String removedNodeId : removedNodes) {
            // Skip if already forwarding for this node
            if (activeForwarders.containsKey(removedNodeId)) {
                log.debug("Already forwarding for removed node: {}", removedNodeId);
                continue;
            }

            log.info("Starting message forwarding for removed node: {} (duration: {})", removedNodeId, FORWARDING_DURATION);

            Disposable forwarder = createForwarder(removedNodeId);
            activeForwarders.put(removedNodeId, forwarder);
        }
    }

    /**
     * Creates a forwarder for a specific removed node.
     *
     * @param removedNodeId The ID of the removed node
     * @return Disposable to cancel the forwarder
     */
    private Disposable createForwarder(String removedNodeId) {
        String deliveryTopic = Topics.deliveryTopicFor(removedNodeId);
        String consumerGroupId = "socket-delivery-" + removedNodeId;

        // Create consumer for the removed node's topic
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrap());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        consumerProps.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");

        ReceiverOptions<String, String> receiverOptions = ReceiverOptions.<String, String>create(consumerProps)
            .subscription(Collections.singleton(deliveryTopic));

        KafkaReceiver<String, String> receiver = KafkaReceiver.create(receiverOptions);

        return receiver.receive()
            .takeUntilOther(Mono.delay(FORWARDING_DURATION)) // Stop after 5 minutes
            .flatMap(record -> {
                String messageValue = record.value();
                Envelope envelope = JsonUtils.readValue(messageValue, Envelope.class);

                if (envelope.getToClientId() == null || envelope.getToClientId().isEmpty()) {
                    log.warn("Received message with null/empty clientId from removed node topic: {}", deliveryTopic);
                    record.receiverOffset().acknowledge();
                    return Mono.empty();
                }

                // Resolve new target node using consistent hash
                String targetNodeId = nodeResolver.apply(envelope.getToClientId());

                if (targetNodeId == null) {
                    log.warn("Could not resolve target node for clientId: {} (no active nodes?)", envelope.getToClientId());
                    record.receiverOffset().acknowledge();
                    return Mono.empty();
                }

                // Don't forward to the same removed node (shouldn't happen, but safety check)
                if (targetNodeId.equals(removedNodeId)) {
                    return redisService.getClientTargetNodeId(envelope.getToClientId()) // get target node from redis
                        .flatMap(nodeId -> forwardMessage(
                            record, nodeId, messageValue, envelope.getToClientId()
                        )); //todo add switch if empty to buffer messages
                }

                return forwardMessage(record, targetNodeId, messageValue, envelope.getToClientId());
            })
            .doOnSubscribe(sub -> log.info("Started forwarding from topic: {}", deliveryTopic))
            .doOnTerminate(() -> {
                log.info(
                    "Stopped forwarding for removed node: {} (after {})", removedNodeId, FORWARDING_DURATION
                );
                activeForwarders.remove(removedNodeId);
            })
            .doOnError(err -> log.error("Error in forwarder for node {}: {}", removedNodeId, err.getMessage()))
            .subscribe();
    }

    private Mono<Void> forwardMessage(ReceiverRecord<String, String> record, String targetNodeId, String messageValue, String clientId) {
        String targetTopic = Topics.deliveryTopicFor(targetNodeId);

        // Forward the message to the new node's topic
        ProducerRecord<String, String> forwardRecord = new ProducerRecord<>(
            targetTopic,
            targetTopic,
            messageValue
        );

        return sender.send(Mono.just(SenderRecord.create(forwardRecord, null)))
            .next()
            .then();
    }

    /**
     * Stops all active forwarders.
     */
    public void stopAll() {
        log.info("Stopping all message forwarders ({} active)", activeForwarders.size());

        for (Map.Entry<String, Disposable> entry : activeForwarders.entrySet()) {
            entry.getValue().dispose();
            log.info("Stopped forwarder for node: {}", entry.getKey());
        }

        activeForwarders.clear();
        sender.close();

        log.info("All message forwarders stopped");
    }

    /**
     * Gets the count of active forwarders.
     *
     * @return Number of active forwarders
     */
    public int getActiveForwarderCount() {
        return activeForwarders.size();
    }

    /**
     * Gets the set of node IDs currently being forwarded.
     *
     * @return Set of removed node IDs with active forwarders
     */
    public Set<String> getActiveForwarderNodes() {
        return Collections.unmodifiableSet(activeForwarders.keySet());
    }
}

