package com.qqsuccubus.loadtest;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class ReactiveRtcLoadTest extends Simulation {

    // Configuration
    private static final String LOAD_BALANCER_URL = System.getProperty("lb.url", "http://localhost:8080");
    private static final int TARGET_CLIENTS = Integer.parseInt(System.getProperty("clients", "9000"));
    private static final int RAMP_UP_MINUTES = Integer.parseInt(System.getProperty("rampup", "10"));
    private static final int TEST_DURATION_MINUTES = Integer.parseInt(System.getProperty("duration", "20"));
    private static final int REQUESTED_INTERVAL = Integer.parseInt(System.getProperty("interval", "3"));
    private static final int MAX_RECONNECT_ATTEMPTS = Integer.parseInt(System.getProperty("maxReconnectAttempts", "10"));

    // MPS limiting (max 1000 MPS) - automatically adjust interval if needed
    private static final int MAX_MPS = 1000;
    private static final int MESSAGE_INTERVAL_SECONDS;

    static {
        // Calculate expected MPS with requested interval
        double requestedMPS = (double) TARGET_CLIENTS / REQUESTED_INTERVAL;

        // If exceeds max, calculate minimum required interval to stay under 3K MPS
        if (requestedMPS > MAX_MPS) {
            MESSAGE_INTERVAL_SECONDS = (int) Math.ceil((double) TARGET_CLIENTS / MAX_MPS);
        } else {
            MESSAGE_INTERVAL_SECONDS = REQUESTED_INTERVAL;
        }
    }

    // Shared counter for sequential client IDs
    private static final AtomicLong clientCounter = new AtomicLong(1);

    // HTTP Protocol for Load Balancer
    HttpProtocolBuilder httpProtocol = http
        .baseUrl(LOAD_BALANCER_URL)
        .acceptHeader("application/json")
        .userAgentHeader("Gatling-LoadTest/1.0");

    // Feeder for sequential client IDs
    Iterator<Map<String, Object>> clientIdFeeder =
        Stream.generate(() -> {
            long id = clientCounter.getAndIncrement();
            return Map.<String, Object>of(
                "clientId", "user" + id,
                "clientIdNum", id
            );
        }).iterator();

    // Helper to map nodeId to WebSocket URL
    // Uses Docker service names when running in Docker, localhost when running locally
    private String nodeIdToWsUrl(String nodeId) {
        if (nodeId == null) {
            return null;
        }

        // Check if running in Docker (by checking if we can resolve service names)
        boolean inDocker = System.getenv("DOCKER_ENV") != null || !LOAD_BALANCER_URL.contains("localhost");

        switch (nodeId) {
            case "socket-node-1":
                return inDocker ? "ws://socket-1:8081/ws" : "ws://localhost:8081/ws";
            case "socket-node-2":
                return inDocker ? "ws://socket-2:8082/ws" : "ws://localhost:8082/ws";
            case "socket-node-3":
                return inDocker ? "ws://socket-3:8083/ws" : "ws://localhost:8083/ws";
            case "node-1":
                return inDocker ? "ws://socket-1:8081/ws" : "ws://localhost:8081/ws";
            case "node-2":
                return inDocker ? "ws://socket-2:8082/ws" : "ws://localhost:8082/ws";
            case "node-3":
                return inDocker ? "ws://socket-3:8083/ws" : "ws://localhost:8083/ws";
            default:
                // Fallback - log warning and use default
                System.err.println("⚠ Unknown nodeId: " + nodeId + ", using default");
                return inDocker ? "ws://socket-1:8081/ws" : "ws://localhost:8081/ws";
        }
    }

    // Scenario: Resolve node and connect via WebSocket
    ChainBuilder resolveAndConnect = exec(session -> {
            // Initialize reconnect counter if not set
            if (!session.contains("reconnectAttempts")) {
                session = session.set("reconnectAttempts", 0);
            }
            // Initialize resolve retry counter
            if (!session.contains("resolveRetries")) {
                session = session.set("resolveRetries", 0);
            }
            return session;
        })
        // Step 1: Resolve node from Load Balancer (with retries)
        .tryMax(5).on(  // Try up to 5 times to resolve
            exec(
                http("Resolve Node")
                    .get("/api/v1/resolve")
                    .queryParam("clientId", "#{clientId}")
                    .check(status().is(200))
                    .check(bodyString().saveAs("responseBody"))
                    .check(jsonPath("$.nodeId").optional().saveAs("nodeId"))
            )
            .pause(Duration.ofMillis(100))  // Small pause between retries
        )
        .exec(session -> {
            String nodeId = session.getString("nodeId");
            String clientId = session.getString("clientId");
            String responseBody = session.getString("responseBody");

            // Debug: Log response if nodeId is null (but only occasionally to reduce spam)
            if (nodeId == null || nodeId.isEmpty()) {
                Long clientNum = session.getLong("clientIdNum");
                if (clientNum != null && clientNum % 100 == 0) {
                    System.err.println("Load balancer returned null nodeId for client " + clientId +
                                     " (response: " + responseBody + ")");
                }
                // Wait longer before marking as failed to give system time to recover
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return session.markAsFailed();
            }

            String wsUrl = nodeIdToWsUrl(nodeId);

            if (wsUrl == null) {
                return session.markAsFailed();
            }

            // Build WebSocket URL with query parameters
            // For first connection: only clientId
            // For reconnection: clientId + resumeOffset=1
            Integer reconnectAttempts = session.getInt("reconnectAttempts");
            boolean isReconnect = reconnectAttempts != null && reconnectAttempts > 0;

            String wsUrlWithParams = isReconnect
                ? wsUrl + "?clientId=" + clientId + "&resumeOffset=1"
                : wsUrl + "?clientId=" + clientId;

            String connectionType = isReconnect ? "(reconnect)" : "(initial)";
            // Reduced logging - only log every 100th connection or reconnections
            Long clientNum = session.getLong("clientIdNum");
            if (isReconnect || (clientNum != null && clientNum % 100 == 0)) {
                System.out.println("Client " + clientId + " -> " + nodeId + " " + connectionType);
            }

            // Initialize counters if they don't exist (important for reconnects)
            Integer currentSent = session.contains("messagesSent") ? session.getInt("messagesSent") : 0;
            Integer currentReceived = session.contains("messagesReceived") ? session.getInt("messagesReceived") : 0;

            return session.set("wsUrlWithParams", wsUrlWithParams)
                         .set("messagesReceived", currentReceived)
                         .set("messagesSent", currentSent);
        })

        // Exit here if resolve failed - don't try to connect
        .exitHereIfFailed()

        // Step 2: Connect to WebSocket with close handler
        .exec(
            ws("Connect WebSocket")
                .connect("#{wsUrlWithParams}")
                .onConnected(
                    exec(session -> {
                        // Reduced logging - don't log every connection
                        return session.set("reconnectAttempts", 0) // Reset reconnect counter on success
                                     .set("wsConnected", true); // Track connection state
                    })
                )
        );

    // Main scenario with automatic reconnection
    ScenarioBuilder userScenario = scenario("WebSocket User")
        .feed(clientIdFeeder)

        // Initialize session attributes early
        .exec(session -> {
            return session.set("messagesSent", 0)
                         .set("messagesReceived", 0)
                         .set("reconnectAttempts", 0)
                         .set("startTime", System.currentTimeMillis());
        })

        .exec(resolveAndConnect)

        // Main loop: send messages with automatic reconnection on failures
        .asLongAs(session -> {
            long startTime = session.getLong("startTime");
            long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
            return elapsedSeconds < (TEST_DURATION_MINUTES * 60);
        }).on(
            // Generate and send message
            exec(session -> {
                // Get current max client number from AtomicLong
                long maxClientNum = clientCounter.get();
                long currentClientNum = session.getLong("clientIdNum");

                // Generate random target client within range [1, maxClientNum)
                long targetNum = maxClientNum <= 1 ? 1 :
                               ThreadLocalRandom.current().nextLong(1, maxClientNum);
                String targetClientId = "user" + targetNum;

                // Generate unique message ID and timestamp
                String msgId = UUID.randomUUID().toString();
                long timestamp = System.currentTimeMillis();
                String clientId = session.getString("clientId");

                // Build message JSON according to specification
                String message = String.format(
                    "{\"msgId\":\"%s\",\"from\":\"%s\",\"toClientId\":\"%s\",\"type\":\"message\"," +
                    "\"payloadJson\":\"{\\\"message\\\":\\\"Hello world, how are you????????????????" +
                    "?????????????????????????????????????????????????????????????????????????????????" +
                    "?????????????????????????????????????????????????????????????????????????????????" +
                    "?????????????????????????????????????????????????????????????????????????????????" +
                    "?????????????????????????????????????????????????????????????????????????????????" +
                    "?????????????????????????????????????????????????????????????????????????????????" +
                    "?????????????????????????????????????????????????????????????????????????????????" +
                    "?????????????????????????????????????????????????????????????????????????????????" +
                    "?????????????????????????????????????????????????????????????????????????????????" +
                    "?????????????????????????????????????????????????????????????????????????????????" +
                    "?????????????????????????????????????????????????????????????????????????????????" +
                    "?????????????????????????????????????????????????????????????????????????????????" +
                    "????????????????????????????!\\\",\\\"roomId\\\":\\\"room1\\\"}\",\"ts\":%d}",
                    msgId, clientId, targetClientId, timestamp
                );

                return session.set("outgoingMessage", message)
                             .set("targetClientId", targetClientId);
            })
            .exec(
                ws("Send Message")
                    .sendText("#{outgoingMessage}")
            )
            .exec(session -> {
                // Increment sent counter
                Integer sent = session.contains("messagesSent") ? session.getInt("messagesSent") : 0;
                return session.set("messagesSent", sent + 1);
            })
            // Track consecutive failures
            .exec(session -> {
                if (session.isFailed()) {
                    Integer failures = session.contains("consecutiveFailures") ?
                                     session.getInt("consecutiveFailures") : 0;
                    return session.set("consecutiveFailures", failures + 1);
                } else {
                    // Reset on success
                    if (session.contains("consecutiveFailures")) {
                        return session.set("consecutiveFailures", 0);
                    }
                }
                return session;
            })
            // Only reconnect if we've had multiple consecutive failures
            .doIf(session -> {
                // Check if we actually have a failure and WebSocket is not connected
                if (session.isFailed()) {
                    Integer failures = session.contains("consecutiveFailures") ?
                                     session.getInt("consecutiveFailures") : 0;
                    return failures >= 3; // Only reconnect after 3 failures
                }
                return false;
            }).then(
                // Close existing WebSocket before reconnecting
                exec(
                    ws("Close Before Reconnect")
                        .close()
                )
                .exec(session -> {
                    Integer attempts = session.getInt("reconnectAttempts");
                    int newAttempts = (attempts != null ? attempts : 0) + 1;
                    // Only log reconnections
                    System.out.println("Reconnecting client " + session.getString("clientId") + " (attempt " + newAttempts + ")");
                    return session.markAsSucceeded() // Clear failed status
                                 .set("reconnectAttempts", newAttempts)
                                 .set("consecutiveFailures", 0); // Reset failure counter
                })
                .pause(Duration.ofSeconds(2))
                .exec(resolveAndConnect)
            )

            // Process any incoming messages
            .exec(
                ws.processUnmatchedMessages((messages, session) -> {
                    if (!messages.isEmpty()) {
                        String clientId = session.getString("clientId");

                        // Check if any message is a ping request from server
                        boolean hasPingRequest = false;
                        for (var msg : messages) {
                            String msgText = msg.toString();
                            if (msgText != null && (msgText.contains("\"type\":\"ping\"") ||
                                                   (msgText.contains("ping") && msgText.contains("request")))) {
                                hasPingRequest = true;
                                break;
                            }
                        }

                        if (hasPingRequest) {
                            // Server sent a ping, we need to respond with pong
                            // Mark that we need to send a ping response
                            session = session.set("needsPongResponse", true);
                        }

                        // Count received messages
                        Integer currentCount = session.getInt("messagesReceived");
                        int newCount = (currentCount != null ? currentCount : 0) + messages.size();
                        session = session.set("messagesReceived", newCount);

                        // Reduced logging - only log every 10000 messages
                        if (newCount % 10000 == 0) {
                            System.out.println("Client " + clientId + " received: " + newCount);
                        }
                    }
                    return session;
                })
            )

            // Send pong response if server sent a ping
            .doIf(session -> session.contains("needsPongResponse") && session.getBoolean("needsPongResponse")).then(
                exec(session -> {
                    String clientId = session.getString("clientId");
                    String pongMessage = String.format(
                        "{\"msgId\":\"%s\",\"from\":\"%s\",\"type\":\"ping\",\"ts\":%d}",
                        UUID.randomUUID().toString(), clientId, System.currentTimeMillis()
                    );
                    return session.set("pongMessage", pongMessage)
                                 .set("needsPongResponse", false);
                })
                .exec(
                    ws("Send Pong")
                        .sendText("#{pongMessage}")
                )
            )

            // Wait before next message
            .pause(Duration.ofSeconds(MESSAGE_INTERVAL_SECONDS))
        )

        // Final stats
        .exec(session -> {
            // Don't print final stats - too much noise
            return session;
        })
        .exec(
            ws("Close Connection")
                .close()
        );

    // Load injection profile
    {
        // Calculate users per second for ramp-up
        double usersPerSecond = (double) TARGET_CLIENTS / (RAMP_UP_MINUTES * 60.0);

        // Calculate expected MPS (Messages Per Second)
        double messagesPerSecPerClient = 1.0 / MESSAGE_INTERVAL_SECONDS;
        double expectedMPS = TARGET_CLIENTS * messagesPerSecPerClient;

        System.out.println("\n" +
            "╔═══════════════════════════════════════════════════════════════════╗\n" +
            "║          Reactive RTC Load Test Configuration                    ║\n" +
            "╠═══════════════════════════════════════════════════════════════════╣\n" +
            "║  Target Clients:         " + String.format("%-39s", String.format("%,d", TARGET_CLIENTS)) + "║\n" +
            "║  Ramp-up Duration:       " + String.format("%-39s", RAMP_UP_MINUTES + " minutes") + "║\n" +
            "║  Test Duration:          " + String.format("%-39s", TEST_DURATION_MINUTES + " minutes") + "║\n" +
            "║  Message Interval:       " + String.format("%-39s", MESSAGE_INTERVAL_SECONDS + " seconds") + "║\n" +
            "║  Users per Second:       " + String.format("%-39s", String.format("%.2f", usersPerSecond)) + "║\n" +
            "║  Expected MPS:           " + String.format("%-39s", String.format("%.0f (max: %d)", expectedMPS, MAX_MPS)) + "║\n" +
            "║  Load Balancer:          " + String.format("%-39s", LOAD_BALANCER_URL) + "║\n" +
            "║  Max Reconnect Attempts: " + String.format("%-39s", MAX_RECONNECT_ATTEMPTS) + "║\n" +
            "╚═══════════════════════════════════════════════════════════════════╝\n"
        );

        setUp(
            userScenario.injectOpen(
                // Ramp up to target over specified duration
                rampUsersPerSec(0).to(usersPerSecond).during(Duration.ofMinutes(RAMP_UP_MINUTES)),
                // Then maintain constant load (existing users continue sending messages)
                nothingFor(Duration.ofMinutes(Math.max(0, TEST_DURATION_MINUTES - RAMP_UP_MINUTES)))
            )
        )
        .protocols(httpProtocol)
        .assertions(
            global().failedRequests().percent().lte(10.0), // Max 10% failure rate
            global().responseTime().percentile3().lte(5000), // 95th percentile < 5s
            global().responseTime().max().lte(30000) // Max 30s response time
        );
    }
}
