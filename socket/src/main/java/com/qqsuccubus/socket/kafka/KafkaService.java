package com.qqsuccubus.socket.kafka;

import com.qqsuccubus.core.msg.ControlMessages;
import com.qqsuccubus.core.msg.Envelope;
import com.qqsuccubus.core.msg.Topics;
import com.qqsuccubus.core.util.JsonUtils;
import com.qqsuccubus.socket.config.SocketConfig;
import com.qqsuccubus.socket.metrics.MetricsService;
import com.qqsuccubus.socket.session.ISessionManager;
import com.qqsuccubus.socket.session.MessageBufferService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;
import reactor.kafka.sender.SenderRecord;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka service for two-hop relay and control message consumption.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Consume DELIVERY_NODE messages (key = nodeId)</li>
 *   <li>Consume CONTROL_RING for ring updates</li>
 *   <li>Produce to DELIVERY_NODE for two-hop relay</li>
 * </ul>
 * Implements IKafkaService for dependency inversion.
 * </p>
 */
public class KafkaService implements IKafkaService {
    private static final Logger log = LoggerFactory.getLogger(KafkaService.class);

    private final SocketConfig config;
    private final ISessionManager sessionManager;
    private final MetricsService metricsService;
    private final MessageBufferService bufferService;

    private final KafkaSender<String, String> sender;
    private final AdminClient adminClient;
    private KafkaReceiver<String, String> deliveryReceiver;
    private KafkaReceiver<String, String> controlReceiver;

    // Topic configuration for per-node delivery topics
    private static final String DELIVERY_TOPIC_PREFIX = "delivery_node_";  // Topic naming: delivery_node_{nodeId}
    private static final int DEFAULT_PARTITIONS = 3;      // Partitions per node topic
    private static final short REPLICATION_FACTOR = 1;    // Replication factor (1 for dev, 3+ for prod)

    public KafkaService(SocketConfig config, ISessionManager sessionManager,
                        MetricsService metricsService, MessageBufferService bufferService) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.metricsService = metricsService;
        this.bufferService = bufferService;

        // Setup producer
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrap());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all"); // Required for idempotent producer
        producerProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
        producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        producerProps.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE); // Recommended for idempotent producer

        SenderOptions<String, String> senderOptions = SenderOptions.create(producerProps);
        this.sender = KafkaSender.create(senderOptions);

        // Setup admin client for topic management
        Map<String, Object> adminProps = new HashMap<>();
        adminProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrap());
        this.adminClient = AdminClient.create(adminProps);

        log.info("Kafka producer and admin client initialized");
    }

    /**
     * Starts Kafka consumers with per-node topics for maximum traffic efficiency.
     * <p>
     * Creates a dedicated topic for this node (if it doesn't exist) and subscribes to it.
     * Each node has its own topic: delivery_node_{nodeId}
     * </p>
     * <p>
     * Benefits:
     * <ul>
     *   <li>100% traffic efficiency - each node only reads its own topic</li>
     *   <li>No filtering needed - topic isolation guarantees correct routing</li>
     *   <li>Independent scaling - add nodes without reconfiguring partitions</li>
     *   <li>Per-node retention - can configure TTL independently</li>
     * </ul>
     * </p>
     * <p>
     * Message routing flow:
     * <pre>
     * 1. Producer: targetNodeId → topic = "delivery_node_{targetNodeId}"
     * 2. Message sent to target node's topic
     * 3. Only target node subscribes to that topic
     * 4. Zero waste - message read by exactly one node
     * </pre>
     * </p>
     *
     * @return Mono completing when consumers are subscribed
     */
    public Mono<Void> start() {
        String currentNodeId = config.getNodeId();
        String deliveryTopic = getDeliveryTopicForNode(currentNodeId);

        log.info("Node {} starting with dedicated delivery topic: {}", currentNodeId, deliveryTopic);

        // Create the node's delivery topic if it doesn't exist
        return createTopicIfNotExists(deliveryTopic, DEFAULT_PARTITIONS, REPLICATION_FACTOR)
            .publishOn(Schedulers.boundedElastic())
            .doOnSuccess(v -> {
                // Consumer for this node's delivery topic
                Map<String, Object> deliveryConsumerProps = new HashMap<>();
                deliveryConsumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrap());
                deliveryConsumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "socket-delivery-" + currentNodeId);
                deliveryConsumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                deliveryConsumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                deliveryConsumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
                deliveryConsumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");
                deliveryConsumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

                ReceiverOptions<String, String> deliveryReceiverOptions = ReceiverOptions.<String, String>create(
                    deliveryConsumerProps
                ).subscription(Collections.singleton(deliveryTopic));  // Subscribe to node-specific topic

                deliveryReceiver = KafkaReceiver.create(deliveryReceiverOptions);

                log.info("Node {} subscribed to delivery topic: {}", currentNodeId, deliveryTopic);

                // Consumer for CONTROL_RING
                Map<String, Object> controlConsumerProps = new HashMap<>();
                controlConsumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrap());
                controlConsumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "socket-control-" + currentNodeId);
                controlConsumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                controlConsumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                controlConsumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

                ReceiverOptions<String, String> controlReceiverOptions = ReceiverOptions.<String, String>create(
                    controlConsumerProps
                ).subscription(Collections.singleton(Topics.CONTROL_RING));

                controlReceiver = KafkaReceiver.create(controlReceiverOptions);

                // Start consuming (fire-and-forget subscriptions for infinite streams)
                listenToDeliveryMessages(currentNodeId).subscribe();
                listenToControlMessages().subscribe();

                log.info("Kafka consumers started for node {}", currentNodeId);
            });
    }

    private Flux<Object> listenToControlMessages() {
        return controlReceiver.receive()
            .flatMap(record -> {
                ControlMessages.RingUpdate ringUpdate = JsonUtils.readValue(
                    record.value(),
                    ControlMessages.RingUpdate.class
                );
                log.info(
                    "Ring update received: version={}, reason={}",
                    ringUpdate.getVersion().getVersion(), ringUpdate.getReason()
                );
                record.receiverOffset().acknowledge();
                return Mono.empty();
            })
            .onErrorContinue((err, obj) -> log.error("Error processing control message", err));
    }

    private Flux<Void> listenToDeliveryMessages(String currentNodeId) {
        return deliveryReceiver.receive()
            .flatMap(record -> {
                try {
                    Envelope envelope = JsonUtils.readValue(record.value(), Envelope.class);
                    log.info(
                        "Kafka message received for node {}: from={}, to={}, msgId={}, envelope.nodeId={}",
                        currentNodeId, envelope.getFrom(), envelope.getToClientId(), 
                        envelope.getMsgId(), envelope.getNodeId()
                    );

                    // Try to deliver the message to the connected client
                    return sessionManager.deliverMessage(envelope)
                        .flatMap(delivered -> {
                            if (delivered) {
                                // Successfully delivered to connected client
                                log.info("Message delivered to connected client: {}", envelope.getToClientId());
                                metricsService.recordDeliverRelay();
                                record.receiverOffset().acknowledge();

                                return Mono.empty();
                            } else {
                                // Client not connected on this node, buffer in Redis for later resume
                                log.info(
                                    "Client {} not connected on node {}, buffering message in Redis",
                                    envelope.getToClientId(), currentNodeId
                                );

                                return bufferService.bufferMessage(envelope)
                                    .doOnSuccess(v -> {
                                        log.info("Message buffered in Redis for client: {}", envelope.getToClientId());
                                        record.receiverOffset().acknowledge();
                                    });
                            }
                        })
                        .onErrorResume(error -> {
                            // Delivery failed (timeout, error, etc.), buffer in Redis as fallback
                            log.warn(
                                "Failed to deliver message to client {} on node {}: {}. Buffering in Redis.",
                                envelope.getToClientId(), currentNodeId, error.getMessage()
                            );

                            return bufferService.bufferMessage(envelope).doOnSuccess(v -> {
                                    log.info(
                                        "Message buffered in Redis after delivery failure for client: {}",
                                        envelope.getToClientId()
                                    );
                                    record.receiverOffset().acknowledge();
                                })
                                .onErrorResume(redisError -> {
                                    log.error(
                                        "CRITICAL: Failed to buffer message in Redis for client {}: {}. " +
                                        "Message will be redelivered by Kafka.",
                                        envelope.getToClientId(), redisError.getMessage(), redisError
                                    );
                                    // Don't acknowledge - let Kafka redeliver
                                    return Mono.empty();
                                });
                        });
                } catch (Exception e) {
                    log.error("Failed to deserialize or process delivery message", e);
                    record.receiverOffset().acknowledge(); // Acknowledge to skip bad messages
                    return Mono.empty();
                }
            })
            .onErrorContinue((err, obj) -> log.error("Error in delivery consumer loop", err));
    }

    /**
     * Publishes a message to Kafka for two-hop relay using per-node topics.
     * <p>
     * Sends the message directly to the target node's dedicated topic,
     * ensuring 100% traffic efficiency - only the target node will read it.
     * </p>
     *
     * @param targetNodeIdHint Target node identifier hint (can be null)
     * @param envelope         Message envelope
     * @return Mono completing when published
     */
    public Mono<Void> publishRelay(String targetNodeIdHint, Envelope envelope) {
        if (envelope == null) {
            log.error("Attempted to relay null envelope");
            return Mono.error(new IllegalArgumentException("Envelope cannot be null"));
        }

        // Try to resolve target node from Redis session, then envelope, then hint
        return Mono.justOrEmpty(envelope.getNodeId()).switchIfEmpty(Mono.justOrEmpty(targetNodeIdHint))
            .flatMap(resolvedNodeId -> {
                log.info(
                    "Serializing envelope for relay: msgId={}, from={}, to={}, targetNode={}",
                    envelope.getMsgId(), envelope.getFrom(), envelope.getToClientId(), resolvedNodeId
                );

                String json = JsonUtils.writeValueAsString(envelope);

                // Get target node's dedicated topic
                String targetTopic = getDeliveryTopicForNode(resolvedNodeId);

                log.info("Sending message to node {} on topic {}", resolvedNodeId, targetTopic);

                // Create record for target node's topic
                ProducerRecord<String, String> record = new ProducerRecord<>(
                    targetTopic,         // Target node's dedicated topic
                    resolvedNodeId,        // Key for logging/debugging
                    json                 // Value
                );

                return sender.send(Mono.just(SenderRecord.create(record, null)))
                    .retry(3)
                    .doOnNext(result -> log.info("Relayed message to node {} (topic {}): msgId={}",
                        resolvedNodeId, targetTopic, envelope.getMsgId()
                    ))
                    .flatMap(message -> Mono.empty())
                    .onErrorResume(err -> {
                        log.warn(
                            "Failed to send message to Kafka for node {}: msgId={}, error={}",
                            resolvedNodeId, envelope.getMsgId(), err.getMessage(), err
                        );
                        return bufferService.bufferMessage(envelope);
                    })
                    .then();
            });
    }

    /**
     * Generates the delivery topic name for a given node.
     * <p>
     * Topic naming convention: delivery_node_{nodeId}
     * Example: delivery_node_socket-node-1
     * </p>
     *
     * @param nodeId Node identifier
     * @return Topic name for the node
     */
    private String getDeliveryTopicForNode(String nodeId) {
        return DELIVERY_TOPIC_PREFIX + nodeId;
    }

    /**
     * Creates a Kafka topic if it doesn't already exist.
     * <p>
     * Uses Kafka AdminClient to create topics dynamically.
     * Idempotent - returns success if topic already exists.
     * </p>
     *
     * @param topicName         Topic to create
     * @param partitions        Number of partitions
     * @param replicationFactor Replication factor
     * @return Mono completing when topic is created or already exists
     */
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
     * Stops Kafka consumers, producer, and admin client.
     *
     * @return Mono completing when stopped
     */
    public Mono<Void> stop() {
        sender.close();
        adminClient.close();
        log.info("Kafka service stopped");
        return Mono.empty();
    }
}

