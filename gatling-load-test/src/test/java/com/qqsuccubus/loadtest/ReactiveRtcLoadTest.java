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

/**
 * Reactive RTC Load Test for Minikube/Kubernetes deployment.
 * 
 * The test flow:
 * 1. Call load-balancer /ws/connect?clientId=xxx to get assigned socket node
 * 2. Connect to WebSocket at /ws/{nodeId}/connect?clientId=xxx via nginx
 * 3. Send messages periodically
 * 
 * Usage:
 *   # First, port-forward nginx gateway:
 *   minikube kubectl -- port-forward -n rtc svc/nginx-gateway-service 8080:80
 *   
 *   # Then run test:
 *   mvn gatling:test -Dclients=100 -Drampup=1 -Dduration=5
 */
public class ReactiveRtcLoadTest extends Simulation {

    // Configuration - defaults work with minikube port-forward
    private static final String GATEWAY_URL = System.getProperty("gateway.url", "http://localhost:8080");
    private static final String WS_GATEWAY_URL = System.getProperty("ws.gateway.url", "ws://localhost:8080");
    private static final int TARGET_CLIENTS = Integer.parseInt(System.getProperty("clients", "100"));
    private static final int RAMP_UP_MINUTES = Integer.parseInt(System.getProperty("rampup", "1"));
    private static final int TEST_DURATION_MINUTES = Integer.parseInt(System.getProperty("duration", "5"));
    private static final int REQUESTED_INTERVAL = Integer.parseInt(System.getProperty("interval", "3"));
    private static final int MAX_RECONNECT_ATTEMPTS = Integer.parseInt(System.getProperty("maxReconnectAttempts", "10"));

    // MPS limiting (max 1000 MPS) - automatically adjust interval if needed
    private static final int MAX_MPS = 1000;
    private static final int MESSAGE_INTERVAL_SECONDS;

    static {
        // Calculate expected MPS with requested interval
        double requestedMPS = (double) TARGET_CLIENTS / REQUESTED_INTERVAL;

        // If exceeds max, calculate minimum required interval to stay under max MPS
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
        .baseUrl(GATEWAY_URL)
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

    // Scenario: Resolve node and connect via WebSocket through nginx
    ChainBuilder resolveAndConnect = exec(session -> {
            // Initialize reconnect counter if not set
            if (!session.contains("reconnectAttempts")) {
                session = session.set("reconnectAttempts", 0);
            }
            if (!session.contains("resolveRetries")) {
                session = session.set("resolveRetries", 0);
            }
            return session;
        })
        // Step 1: Call load-balancer /api/v1/resolve to get assigned socket node
        .tryMax(5).on(
            exec(
                http("Resolve Node")
                    .get("/api/v1/resolve")
                    .queryParam("clientId", "#{clientId}")
                    .check(status().is(200))
                    .check(bodyString().saveAs("responseBody"))
                    .check(jsonPath("$.nodeId").optional().saveAs("nodeId"))
            )
            .pause(Duration.ofMillis(100))
        )
        .exec(session -> {
            String nodeId = session.getString("nodeId");
            String clientId = session.getString("clientId");
            String responseBody = session.getString("responseBody");

            // Debug: Log response if nodeId is null
            if (nodeId == null || nodeId.isEmpty()) {
                Long clientNum = session.getLong("clientIdNum");
                if (clientNum != null && clientNum % 100 == 0) {
                    System.err.println("Load balancer returned null nodeId for client " + clientId +
                                     " (response: " + responseBody + ")");
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return session.markAsFailed();
            }

            // Build WebSocket URL: /ws/{nodeId}/connect?clientId=xxx
            // Nginx will route this to the specific socket node based on nodeId
            Integer reconnectAttempts = session.getInt("reconnectAttempts");
            boolean isReconnect = reconnectAttempts != null && reconnectAttempts > 0;

            String wsPath = "/ws/" + nodeId + "/connect";
            String wsUrl = isReconnect
                ? WS_GATEWAY_URL + wsPath + "?clientId=" + clientId + "&resumeOffset=1"
                : WS_GATEWAY_URL + wsPath + "?clientId=" + clientId;

            String connectionType = isReconnect ? "(reconnect)" : "(initial)";
            Long clientNum = session.getLong("clientIdNum");
            if (isReconnect || (clientNum != null && clientNum % 100 == 0)) {
                System.out.println("Client " + clientId + " -> " + nodeId + " " + connectionType);
            }

            // Initialize counters
            Integer currentSent = session.contains("messagesSent") ? session.getInt("messagesSent") : 0;
            Integer currentReceived = session.contains("messagesReceived") ? session.getInt("messagesReceived") : 0;

            return session.set("wsUrl", wsUrl)
                         .set("nodeId", nodeId)
                         .set("messagesReceived", currentReceived)
                         .set("messagesSent", currentSent);
        })

        // Exit here if resolve failed
        .exitHereIfFailed()

        // Step 2: Connect to WebSocket via nginx gateway
        .exec(
            ws("Connect WebSocket")
                .connect("#{wsUrl}")
                .onConnected(
                    exec(session -> {
                        return session.set("reconnectAttempts", 0)
                                     .set("wsConnected", true);
                    })
                )
        );

    // Main scenario with automatic reconnection
    ScenarioBuilder userScenario = scenario("WebSocket User")
        .feed(clientIdFeeder)

        // Initialize session attributes
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
                long maxClientNum = clientCounter.get();
                long currentClientNum = session.getLong("clientIdNum");

                // Generate random target client
                long targetNum = maxClientNum <= 1 ? 1 :
                               ThreadLocalRandom.current().nextLong(1, maxClientNum);
                String targetClientId = "user" + targetNum;

                String msgId = UUID.randomUUID().toString();
                long timestamp = System.currentTimeMillis();
                String clientId = session.getString("clientId");

                // Build message JSON
                String message = String.format(
                    "{\"msgId\":\"%s\",\"from\":\"%s\",\"toClientId\":\"%s\",\"type\":\"message\"," +
                    "\"payloadJson\":\"{\\\"message\\\":\\\"Hello from load test!\\\",\\\"roomId\\\":\\\"room1\\\"}\",\"ts\":%d}",
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
                    if (session.contains("consecutiveFailures")) {
                        return session.set("consecutiveFailures", 0);
                    }
                }
                return session;
            })
            // Reconnect after 3 consecutive failures
            .doIf(session -> {
                if (session.isFailed()) {
                    Integer failures = session.contains("consecutiveFailures") ?
                                     session.getInt("consecutiveFailures") : 0;
                    return failures >= 3;
                }
                return false;
            }).then(
                exec(
                    ws("Close Before Reconnect")
                        .close()
                )
                .exec(session -> {
                    Integer attempts = session.getInt("reconnectAttempts");
                    int newAttempts = (attempts != null ? attempts : 0) + 1;
                    System.out.println("Reconnecting client " + session.getString("clientId") + " (attempt " + newAttempts + ")");
                    return session.markAsSucceeded()
                                 .set("reconnectAttempts", newAttempts)
                                 .set("consecutiveFailures", 0);
                })
                .pause(Duration.ofSeconds(2))
                .exec(resolveAndConnect)
            )

            // Process incoming messages
            .exec(
                ws.processUnmatchedMessages((messages, session) -> {
                    if (!messages.isEmpty()) {
                        String clientId = session.getString("clientId");

                        // Check for ping request
                        boolean hasPingRequest = false;
                        for (var msg : messages) {
                            String msgText = msg.toString();
                            if (msgText != null && msgText.contains("\"type\":\"ping\"")) {
                                hasPingRequest = true;
                                break;
                            }
                        }

                        if (hasPingRequest) {
                            session = session.set("needsPongResponse", true);
                        }

                        Integer currentCount = session.getInt("messagesReceived");
                        int newCount = (currentCount != null ? currentCount : 0) + messages.size();
                        session = session.set("messagesReceived", newCount);

                        if (newCount % 1000 == 0) {
                            System.out.println("Client " + clientId + " received: " + newCount);
                        }
                    }
                    return session;
                })
            )

            // Send pong response if needed
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

        // Close connection at end
        .exec(
            ws("Close Connection")
                .close()
        );

    // Load injection profile
    {
        double usersPerSecond = (double) TARGET_CLIENTS / (RAMP_UP_MINUTES * 60.0);
        double messagesPerSecPerClient = 1.0 / MESSAGE_INTERVAL_SECONDS;
        double expectedMPS = TARGET_CLIENTS * messagesPerSecPerClient;

        System.out.println("\n" +
            "╔═══════════════════════════════════════════════════════════════════╗\n" +
            "║          Reactive RTC Load Test (Minikube)                        ║\n" +
            "╠═══════════════════════════════════════════════════════════════════╣\n" +
            "║  Gateway URL:            " + String.format("%-39s", GATEWAY_URL) + "║\n" +
            "║  WebSocket URL:          " + String.format("%-39s", WS_GATEWAY_URL) + "║\n" +
            "║  Target Clients:         " + String.format("%-39s", String.format("%,d", TARGET_CLIENTS)) + "║\n" +
            "║  Ramp-up Duration:       " + String.format("%-39s", RAMP_UP_MINUTES + " minutes") + "║\n" +
            "║  Test Duration:          " + String.format("%-39s", TEST_DURATION_MINUTES + " minutes") + "║\n" +
            "║  Message Interval:       " + String.format("%-39s", MESSAGE_INTERVAL_SECONDS + " seconds") + "║\n" +
            "║  Users per Second:       " + String.format("%-39s", String.format("%.2f", usersPerSecond)) + "║\n" +
            "║  Expected MPS:           " + String.format("%-39s", String.format("%.0f (max: %d)", expectedMPS, MAX_MPS)) + "║\n" +
            "║  Max Reconnect Attempts: " + String.format("%-39s", MAX_RECONNECT_ATTEMPTS) + "║\n" +
            "╚═══════════════════════════════════════════════════════════════════╝\n"
        );

        setUp(
            userScenario.injectOpen(
                rampUsersPerSec(0).to(usersPerSecond).during(Duration.ofMinutes(RAMP_UP_MINUTES)),
                nothingFor(Duration.ofMinutes(Math.max(0, TEST_DURATION_MINUTES - RAMP_UP_MINUTES)))
            )
        )
        .protocols(httpProtocol)
        .assertions(
            global().failedRequests().percent().lte(10.0),
            global().responseTime().percentile3().lte(5000),
            global().responseTime().max().lte(30000)
        );
    }
}
