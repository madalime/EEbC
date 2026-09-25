package edu.kit.kastel.tva.eebc.verifier.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static edu.kit.kastel.tva.eebc.verifier.Fixtures.MAPPER;

/**
 * A black-box client of the Verifier API, as WebCorC uses it, built on the JDK's {@link HttpClient} and
 * {@link WebSocket}. Every stream it reads is checked against the stream rules of the Verifier specification
 * (only {@code log}/{@code done} objects, {@code done} exactly once and last, then close {@code 1000}), and every
 * result against the result shape.
 */
final class VerifierClient {
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String base;
    private final String wsBase;

    VerifierClient(int port) {
        this.base = "http://127.0.0.1:" + port;
        this.wsBase = "ws://127.0.0.1:" + port;
    }

    /** What one status stream delivered. */
    record Stream(List<JsonNode> messages, int closeCode) {
        List<String> logLines() {
            return messages.stream().filter(m -> m.get("type").asText().equals("log"))
                    .map(m -> m.get("message").asText()).toList();
        }

        JsonNode done() {
            return messages.getLast();
        }
    }

    /** A whole job: the stream and the result. */
    record Run(String id, Stream stream, JsonNode result) {
        List<String> log() {
            return stream.logLines();
        }

        boolean proven() {
            return stream.done().get("proven").asBoolean();
        }

        String status() {
            return stream.done().get("status").asText();
        }
    }

    static String requestBody(JsonNode program, JsonNode settings) {
        ObjectNode body = MAPPER.createObjectNode();
        body.set("program", program);
        body.putArray("files").addObject().put("path", "javaSrc/Util.java").put("content", "class Util {}");
        body.set("settings", settings);
        return body.toString();
    }

    HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    HttpResponse<String> post(String id, String body) {
        return send(HttpRequest.newBuilder(URI.create(base + "/jobs/" + id))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Starts a job (asserting {@code 202} with no body), reads its stream and fetches its result. */
    Run run(JsonNode program, JsonNode settings) {
        String id = UUID.randomUUID().toString();
        start(id, requestBody(program, settings));
        Stream stream = stream(id);
        return new Run(id, stream, result(id));
    }

    void start(String id, String body) {
        HttpResponse<String> response = post(id, body);
        Assertions.assertEquals(202, response.statusCode(), response.body());
        Assertions.assertEquals("", response.body());
    }

    /** Fetches and checks the result of a finished job. */
    JsonNode result(String id) {
        HttpResponse<String> response = get("/jobs/" + id + "/result");
        Assertions.assertEquals(200, response.statusCode(), response.body());
        Assertions.assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        JsonNode result = readJson(response.body());
        Assertions.assertTrue(result.isObject());
        for (Iterator<Map.Entry<String, JsonNode>> it = result.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            Assertions.assertTrue(entry.getKey().matches("-?\\d+"), "key " + entry.getKey());
            Assertions.assertTrue(entry.getValue().isObject());
            Assertions.assertTrue(entry.getValue().get("proven").isBoolean());
            Assertions.assertTrue(entry.getValue().get("status").isTextual());
            Assertions.assertEquals(Set.of("proven", "status"), fieldNames(entry.getValue()));
        }
        return result;
    }

    /** Opens the status stream and reads it until the Verifier closes it; checks the stream rules. */
    Stream stream(String id) {
        Collector collector = new Collector();
        try {
            http.newWebSocketBuilder().buildAsync(URI.create(wsBase + "/jobs/" + id), collector)
                    .get(10, TimeUnit.SECONDS);
            int closeCode = collector.closed.get(60, TimeUnit.SECONDS);
            List<JsonNode> messages = collector.messages.stream().map(VerifierClient::readJson).toList();
            checkStream(messages);
            return new Stream(messages, closeCode);
        } catch (Exception e) {
            throw new IllegalStateException("status stream of job " + id + " failed", e);
        }
    }

    /** @return the HTTP status with which the WebSocket handshake was refused, or {@code 101} if it succeeded */
    int handshakeStatus(String id) {
        try {
            WebSocket ws = http.newWebSocketBuilder()
                    .buildAsync(URI.create(wsBase + "/jobs/" + id), new Collector())
                    .get(10, TimeUnit.SECONDS);
            ws.abort();
            return 101;
        } catch (ExecutionException | CompletionException e) {
            if (e.getCause() instanceof WebSocketHandshakeException handshake) {
                return handshake.getResponse().statusCode();
            }
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The response of a handshake that was refused. */
    HttpResponse<?> refusedHandshake(String id) {
        try {
            http.newWebSocketBuilder().buildAsync(URI.create(wsBase + "/jobs/" + id), new Collector())
                    .get(10, TimeUnit.SECONDS).abort();
            throw new AssertionError("handshake for " + id + " succeeded");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof WebSocketHandshakeException handshake) {
                return handshake.getResponse();
            }
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void checkStream(List<JsonNode> messages) {
        Assertions.assertFalse(messages.isEmpty(), "no messages");
        int done = 0;
        for (JsonNode message : messages) {
            Assertions.assertTrue(message.isObject(), message.toString());
            String type = message.path("type").asText();
            switch (type) {
                case "log" -> {
                    Assertions.assertTrue(message.get("message").isTextual(), message.toString());
                    Assertions.assertEquals(Set.of("type", "message"), fieldNames(message));
                }
                case "done" -> {
                    done++;
                    Assertions.assertTrue(message.get("proven").isBoolean(), message.toString());
                    Assertions.assertTrue(message.get("status").isTextual(), message.toString());
                    Assertions.assertEquals(Set.of("type", "proven", "status"), fieldNames(message));
                }
                default -> Assertions.fail("unexpected message " + message);
            }
        }
        Assertions.assertEquals(1, done, "done exactly once");
        Assertions.assertEquals("done", messages.getLast().get("type").asText(), "done last");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    static JsonNode readJson(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new AssertionError("not JSON: " + text, e);
        }
    }

    private static final class Collector implements WebSocket.Listener {
        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        private final StringBuilder partial = new StringBuilder();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
            messages.add("<binary frame>");
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
        }
    }
}
