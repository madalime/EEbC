package edu.kit.kastel.tva.eebc.verifier.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.kit.kastel.tva.eebc.verifier.Fixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static edu.kit.kastel.tva.eebc.verifier.Fixtures.MAPPER;
import static edu.kit.kastel.tva.eebc.verifier.Fixtures.program;
import static edu.kit.kastel.tva.eebc.verifier.Fixtures.settings;

/**
 * Seam 1: the Verifier's HTTP/WebSocket API as a black box, driven like WebCorC drives it. The client checks the
 * stream rules and the result shape of every job (see {@link VerifierClient}).
 */
class VerifierServerTest {
    private static VerifierServer server;
    private static VerifierClient client;

    @BeforeAll
    static void startServer() {
        server = new VerifierServer(testConfig(256L * 1024 * 1024, Duration.ofMinutes(60)),
                Fixtures.registryWithHugeModel()).start();
        client = new VerifierClient(server.port());
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    static Config testConfig(long stackBytes, Duration ttl) {
        Duration sweep = ttl.compareTo(Duration.ofSeconds(1)) < 0 ? ttl : Duration.ofSeconds(1);
        return new Config(0, "127.0.0.1", 4, stackBytes, ttl, sweep);
    }

    /** Builds the expected result map from {@code id, proven, status} triples. */
    private static JsonNode expected(Object... triples) {
        ObjectNode result = MAPPER.createObjectNode();
        for (int i = 0; i < triples.length; i += 3) {
            result.putObject(String.valueOf(triples[i]))
                    .put("proven", (Boolean) triples[i + 1])
                    .put("status", (String) triples[i + 2]);
        }
        return result;
    }

    // ---- status table ----

    @Test
    void executedWithinBound() {
        VerifierClient.Run run = client.run(program("straight"), settings("50", "unit", ""));
        Assertions.assertEquals(expected(
                1, true, "energy estimate: 6 / 50",
                2, true, "energy estimate: 2 / 50",
                3, true, "energy estimate: 4 / 50"), run.result());
        Assertions.assertTrue(run.proven());
        Assertions.assertEquals("total energy estimate: 6 / 50", run.status());
        Assertions.assertEquals(List.of(
                "Hardware model: unit, upper bound: 50, initial state: all 0",
                "3 proven, 0 failed, 0 not executed"), run.log());
    }

    @Test
    void executedOverBound() {
        VerifierClient.Run run = client.run(program("straight"), settings("3", "unit", ""));
        Assertions.assertEquals(expected(
                1, false, "energy estimate: 6 / 3 (exceeds bound)",
                2, true, "energy estimate: 2 / 3",
                3, false, "energy estimate: 4 / 3 (exceeds bound)"), run.result());
        Assertions.assertFalse(run.proven());
        Assertions.assertEquals("total energy estimate: 6 / 3", run.status());
        // budget failures are not logged
        Assertions.assertEquals(List.of(
                "Hardware model: unit, upper bound: 3, initial state: all 0",
                "1 proven, 2 failed, 0 not executed"), run.log());
    }

    @Test
    void executedSeveralTimesShowsTheMaximum() {
        VerifierClient.Run run = client.run(program("loopSelection"), settings("15", "unit", "n=3"));
        Assertions.assertEquals(expected(
                1, false, "energy estimate: 57 / 15 (exceeds bound)",
                2, true, "energy estimate: 2 / 15",
                3, false, "energy estimate: 55 / 15 (exceeds bound)",
                4, false, "energy estimate: max 20 / 15 (exceeds bound)",
                5, true, "energy estimate: max 4 / 15",
                6, true, "energy estimate: 8 / 15"), run.result());
        Assertions.assertEquals("total energy estimate: 57 / 15", run.status());
    }

    @Test
    void loopBodyRunningOnceHasNoMaximum() {
        VerifierClient.Run run = client.run(program("loop"), settings("50", "unit", "n=1"));
        Assertions.assertEquals(expected(
                1, true, "energy estimate: 13 / 50",
                2, true, "energy estimate: 2 / 50",
                3, true, "energy estimate: 11 / 50",
                4, true, "energy estimate: 4 / 50"), run.result());
    }

    @Test
    void notExecutedBranch() {
        VerifierClient.Run run = client.run(program("branch"), settings("50", "unit", "x=5"));
        Assertions.assertEquals(expected(
                1, true, "energy estimate: 6 / 50",
                2, true, "energy estimate: 2 / 50",
                3, true, "not executed for this input"), run.result());
        Assertions.assertTrue(run.proven());
        Assertions.assertEquals("total energy estimate: 6 / 50", run.status());
        Assertions.assertEquals("2 proven, 0 failed, 1 not executed", run.log().getLast());

        VerifierClient.Run other = client.run(program("branch"), settings("50", "unit", "x=0"));
        Assertions.assertEquals(expected(
                1, true, "energy estimate: 12 / 50",
                2, true, "not executed for this input",
                3, true, "energy estimate: 4 / 50"), other.result());
    }

    @Test
    void notExecutedLoopBody() {
        VerifierClient.Run run = client.run(program("loop"), settings("50", "unit", "n=0"));
        Assertions.assertEquals(expected(
                1, true, "energy estimate: 5 / 50",
                2, true, "energy estimate: 2 / 50",
                3, true, "energy estimate: 3 / 50",
                4, true, "not executed for this input"), run.result());
        Assertions.assertTrue(run.proven());
    }

    @Test
    void unrefinedLeafAndItsAncestors() {
        VerifierClient.Run run = client.run(program("unrefined"), settings("50", "unit", ""));
        Assertions.assertEquals(expected(
                1, false, "energy estimate incomplete: 2 / 50 (contains unrefined statements)",
                2, false, "energy estimate incomplete: statement not yet refined",
                3, true, "energy estimate: 2 / 50"), run.result());
        Assertions.assertFalse(run.proven());
        Assertions.assertEquals("total energy estimate: 2 / 50", run.status());
    }

    /** EEbC's loop typing state ignores its body; the layer still reports the loop and root as incomplete. */
    @Test
    void unrefinedStatementInsideALoop() {
        VerifierClient.Run run = client.run(program("unrefinedLoop"), settings("50", "unit", "n=2"));
        Assertions.assertEquals(expected(
                1, false, "energy estimate incomplete: 13 / 50 (contains unrefined statements)",
                2, true, "energy estimate: 2 / 50",
                3, false, "energy estimate incomplete: 11 / 50 (contains unrefined statements)",
                4, false, "energy estimate incomplete: statement not yet refined"), run.result());
        Assertions.assertFalse(run.proven());
    }

    @Test
    void loopWithoutVariant() {
        VerifierClient.Run run = client.run(program("noVariant"), settings("50", "unit", "n=3"));
        Assertions.assertEquals(expected(
                1, false, "not analysed: contains statement 'loop' that could not be analysed",
                2, false, "not analysed: loop has no variant",
                3, false, "not analysed: start state unknown (after 'loop')",
                4, false, "not analysed: start state unknown (after 'loop')"), run.result());
        Assertions.assertFalse(run.proven());
        Assertions.assertEquals("total energy estimate unavailable", run.status());
        Assertions.assertEquals(List.of(
                "Hardware model: unit, upper bound: 50, initial state: n=3",
                "Statement 'loop' (id 2): loop has no variant",
                "0 proven, 4 failed, 0 not executed"), run.log());
    }

    @Test
    void unsupportedCodePropagatesToAncestorsAndLaterStatements() {
        VerifierClient.Run run = client.run(program("unsupported"), settings("50", "unit", ""));
        Assertions.assertEquals(expected(
                1, false, "not analysed: contains statement 'inc' that could not be analysed",
                2, true, "energy estimate: 2 / 50",
                3, false, "not analysed: contains statement 'inc' that could not be analysed",
                4, false, "not analysed: unsupported code: statement 'i++;' is not supported",
                5, false, "not analysed: start state unknown (after 'inc')"), run.result());
        Assertions.assertFalse(run.proven());
        Assertions.assertEquals("total energy estimate unavailable", run.status());
        Assertions.assertEquals(List.of(
                "Hardware model: unit, upper bound: 50, initial state: all 0",
                "Statement 'inc' (id 4): unsupported code: statement 'i++;' is not supported",
                "1 proven, 4 failed, 0 not executed"), run.log());
    }

    @Test
    void runtimeFailureFailsEveryStatement() {
        VerifierClient.Run run = client.run(program("division"), settings("50", "unit", ""));
        Assertions.assertEquals(expected(
                1, false, "not analysed: analysis failed (division by zero)",
                2, false, "not analysed: analysis failed (division by zero)",
                3, false, "not analysed: analysis failed (division by zero)"), run.result());
        Assertions.assertFalse(run.proven());
        Assertions.assertEquals("total energy estimate unavailable", run.status());
        Assertions.assertEquals(List.of(
                "Hardware model: unit, upper bound: 50, initial state: all 0",
                "Analysis failed: division by zero",
                "0 proven, 3 failed, 0 not executed"), run.log());
    }

    @Test
    void stackOverflowIsARuntimeFailure() {
        try (VerifierServer small = new VerifierServer(testConfig(256 * 1024, Duration.ofMinutes(60))).start()) {
            VerifierClient.Run run = new VerifierClient(small.port())
                    .run(program("loop"), settings("50", "unit", "n=1000000"));
            for (int id = 1; id <= 4; id++) {
                Assertions.assertEquals("not analysed: analysis failed (stack overflow)",
                        run.result().get(String.valueOf(id)).get("status").asText());
            }
            Assertions.assertTrue(run.log().contains("Analysis failed: stack overflow"));
            Assertions.assertFalse(run.proven());
        }
    }

    @Test
    void overflowIsNeverProven() {
        VerifierClient.Run run = client.run(program("twoAssignments"), settings("3000000000", "huge", ""));
        Assertions.assertEquals(expected(
                1, false, "energy estimate overflowed",
                2, true, "energy estimate: 2000000000 / 3000000000",
                3, true, "energy estimate: 2000000000 / 3000000000"), run.result());
        Assertions.assertFalse(run.proven());
        Assertions.assertEquals("total energy estimate unavailable", run.status());
    }

    @Test
    void unknownInitialVariableIsLoggedAsWarning() {
        VerifierClient.Run run = client.run(program("loop"), settings("50", "unit", "n=2, m=4"));
        Assertions.assertEquals(List.of(
                "Hardware model: unit, upper bound: 50, initial state: n=2, m=4",
                "Initial state: 'm' is not a program variable",
                "4 proven, 0 failed, 0 not executed"), run.log());
    }

    @Test
    void missingSettingsTakeTheirDefaults() {
        VerifierClient.Run run = client.run(program("straight"), MAPPER.createObjectNode());
        Assertions.assertEquals("Hardware model: unit, upper bound: 0, initial state: all 0", run.log().getFirst());
        Assertions.assertEquals("total energy estimate: 6 / 0", run.status());
        Assertions.assertFalse(run.proven());
    }

    @Test
    void unknownFieldsAndSettingsAreIgnored() {
        ObjectNode program = (ObjectNode) program("straight");
        program.put("comment", "newer WebCorC");
        program.putObject("postCondition").put("condition", "energy <= 5");
        ((ObjectNode) program.get("statement")).put("colour", "red");
        ObjectNode settings = settings("50", "unit", "");
        settings.put("futureSetting", "x");
        ObjectNode body = (ObjectNode) VerifierClient.readJson(VerifierClient.requestBody(program, settings));
        body.put("somethingNew", 42);

        String id = UUID.randomUUID().toString();
        client.start(id, body.toString());
        VerifierClient.Stream stream = client.stream(id);
        Assertions.assertTrue(stream.done().get("proven").asBoolean());
        Assertions.assertEquals(3, client.result(id).size());
    }

    // ---- validation: 400 as problem+json ----

    static Stream<Arguments> invalidRequests() {
        String straight = program("straight").toString();
        return Stream.of(
                Arguments.of("not JSON", "{ nope", "Request body is not valid JSON"),
                Arguments.of("empty body", "", "Request body must be a JSON object"),
                Arguments.of("array body", "[]", "Request body must be a JSON object"),
                Arguments.of("missing program", "{\"files\": [], \"settings\": {}}", "Request is missing 'program'"),
                Arguments.of("missing files", "{\"program\": " + straight + ", \"settings\": {}}",
                        "Request is missing 'files'"),
                Arguments.of("missing settings", "{\"program\": " + straight + ", \"files\": []}",
                        "Request is missing 'settings'"),
                Arguments.of("files not an array", "{\"program\": " + straight + ", \"files\": {}, \"settings\": {}}",
                        "'files' must be an array"),
                Arguments.of("settings not an object",
                        "{\"program\": " + straight + ", \"files\": [], \"settings\": []}",
                        "'settings' must be a JSON object"),
                Arguments.of("program not an object", "{\"program\": 3, \"files\": [], \"settings\": {}}",
                        "Program: program must be a JSON object"),
                Arguments.of("unknown statement type", body("""
                        { "name": "p", "javaVariables": [], "statement":
                          { "id": 1, "name": "root", "type": "COMPOSITION",
                            "firstStatement": { "id": 2, "name": "weird", "type": "LOOP" },
                            "secondStatement": { "id": 3, "name": "s", "type": "SKIP" } } }
                        """, "{}"), "Statement 'weird' (id 2): unknown type 'LOOP'"),
                Arguments.of("missing child", body("""
                        { "name": "p", "javaVariables": [], "statement":
                          { "id": 1, "name": "root", "type": "COMPOSITION",
                            "firstStatement": { "id": 2, "name": "s", "type": "SKIP" } } }
                        """, "{}"), "Statement 'root' (id 1): missing 'secondStatement'"),
                Arguments.of("guards/commands mismatch", body("""
                        { "name": "p", "javaVariables": [], "statement":
                          { "id": 7, "name": "if", "type": "SELECTION",
                            "guards": [ { "condition": "i > 0" }, { "condition": "i < 0" } ],
                            "commands": [ { "id": 8, "name": "s", "type": "SKIP" } ] } }
                        """, "{}"), "Statement 'if' (id 7): selection has 2 guards but 1 commands"),
                Arguments.of("duplicate ids", body("""
                        { "name": "p", "javaVariables": [], "statement":
                          { "id": 1, "name": "root", "type": "COMPOSITION",
                            "firstStatement": { "id": 2, "name": "a", "type": "SKIP" },
                            "secondStatement": { "id": 2, "name": "b", "type": "SKIP" } } }
                        """, "{}"), "Statement 'b' (id 2): duplicate id 2"),
                Arguments.of("negative bound", body(straight, "{\"upperBound\": \"-1\"}"),
                        "Energy upper bound must be a whole number ≥ 0, got '-1'"),
                Arguments.of("fractional bound", body(straight, "{\"upperBound\": \"2.5\"}"),
                        "Energy upper bound must be a whole number ≥ 0, got '2.5'"),
                Arguments.of("unknown hardware model", body(straight, "{\"hardwareModel\": \"fast\"}"),
                        "Unknown hardware model 'fast' (available: unit, huge)"),
                Arguments.of("malformed initial state", body(straight, "{\"initialState\": \"n==10\"}"),
                        "Initial state: expected name=integer at 'n==10'"),
                Arguments.of("initial value twice", body(straight, "{\"initialState\": \"x=1, x=2\"}"),
                        "Initial state: 'x' is given twice")
        );
    }

    private static String body(String program, String settings) {
        return "{\"program\": " + program + ", \"files\": [], \"settings\": " + settings + "}";
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void invalidRequestIsRejected(String description, String body, String detail) {
        String id = UUID.randomUUID().toString();
        HttpResponse<String> response = client.post(id, body);
        assertProblem(response, 400, "Bad Request", detail, "/jobs/" + id);
        // no job was created
        Assertions.assertEquals(404, client.handshakeStatus(id));
    }

    private static void assertProblem(HttpResponse<?> response, int status, String title, String detail,
                                      String instance) {
        Assertions.assertEquals(status, response.statusCode());
        Assertions.assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/problem+json"), response.headers().toString());
        if (response.body() instanceof String text) {
            JsonNode problem = VerifierClient.readJson(text);
            Assertions.assertEquals("about:blank", problem.get("type").asText());
            Assertions.assertEquals(title, problem.get("title").asText());
            Assertions.assertEquals(status, problem.get("status").asInt());
            if (detail != null) {
                Assertions.assertEquals(detail, problem.get("detail").asText());
            }
            Assertions.assertEquals(instance, problem.get("instance").asText());
        }
    }

    // ---- 404 / 409 ----

    @Test
    void duplicateJobIdIsAConflict() {
        String id = UUID.randomUUID().toString();
        String body = VerifierClient.requestBody(program("straight"), settings("50", null, null));
        client.start(id, body);
        assertProblem(client.post(id, body), 409, "Conflict", "A job with id '" + id + "' already exists",
                "/jobs/" + id);
        client.stream(id);
        client.result(id);
    }

    @Test
    void secondStatusStreamIsAConflict() {
        String id = UUID.randomUUID().toString();
        client.start(id, VerifierClient.requestBody(program("straight"), settings("50", null, null)));
        client.stream(id);
        HttpResponse<?> refused = client.refusedHandshake(id);
        Assertions.assertEquals(409, refused.statusCode());
        client.result(id);
    }

    @Test
    void unknownJobIsNotFound() {
        String id = UUID.randomUUID().toString();
        Assertions.assertEquals(404, client.handshakeStatus(id));
        assertProblem(client.get("/jobs/" + id + "/result"), 404, "Not Found", "Unknown job '" + id + "'",
                "/jobs/" + id + "/result");
    }

    @Test
    void jobIsDeletedOnceItsResultIsFetched() {
        VerifierClient.Run run = client.run(program("straight"), settings("50", null, null));
        Assertions.assertEquals(404, client.get("/jobs/" + run.id() + "/result").statusCode());
        Assertions.assertEquals(404, client.handshakeStatus(run.id()));
        // the id may be used again afterwards
        client.start(run.id(), VerifierClient.requestBody(program("straight"), settings("50", null, null)));
        client.stream(run.id());
        client.result(run.id());
    }

    @Test
    void unfetchedFinishedJobsExpire() throws Exception {
        try (VerifierServer shortLived = new VerifierServer(testConfig(16L * 1024 * 1024, Duration.ofMillis(200)))
                .start()) {
            VerifierClient shortClient = new VerifierClient(shortLived.port());
            String id = UUID.randomUUID().toString();
            shortClient.start(id, VerifierClient.requestBody(program("straight"), settings("50", null, null)));
            // the first handshake succeeds (the job is kept), later ones get 409 until the job has expired
            long deadline = System.currentTimeMillis() + 10_000;
            int status;
            do {
                Thread.sleep(100);
                status = shortClient.handshakeStatus(id);
            } while (status != 404 && System.currentTimeMillis() < deadline);
            Assertions.assertEquals(404, status);
            Assertions.assertEquals(404, shortClient.get("/jobs/" + id + "/result").statusCode());
        }
    }

    @Test
    void unknownRouteIsAProblem() {
        assertProblem(client.get("/nothing-here"), 404, "Not Found", null, "/nothing-here");
    }

    // ---- conformance checklist (section 7) ----

    @Test
    void selfDescriptionFollowsTheSpecification() {
        HttpResponse<String> response = client.get("/description");
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        JsonNode description = VerifierClient.readJson(response.body());

        Assertions.assertEquals(Set.of("label", "enabled", "toggleable", "statusPlaceholder", "settings"),
                names(description));
        Assertions.assertEquals("Energy (EEbC)", description.get("label").asText());
        Assertions.assertFalse(description.get("enabled").asBoolean());
        Assertions.assertTrue(description.get("enabled").isBoolean());
        Assertions.assertTrue(description.get("toggleable").asBoolean());
        Assertions.assertEquals("Energy not yet analysed", description.get("statusPlaceholder").asText());

        JsonNode settings = description.get("settings");
        Assertions.assertEquals(3, settings.size());

        JsonNode upperBound = settings.get(0);
        Assertions.assertEquals(Set.of("id", "type", "valueType", "label", "description", "step", "range",
                "required", "default"), names(upperBound));
        Assertions.assertEquals("upperBound", upperBound.get("id").asText());
        Assertions.assertEquals("text", upperBound.get("type").asText());
        Assertions.assertEquals("number", upperBound.get("valueType").asText());
        Assertions.assertEquals("Energy upper bound", upperBound.get("label").asText());
        Assertions.assertEquals(1, upperBound.get("step").asInt());
        Assertions.assertEquals(MAPPER.createObjectNode().put("min", 0), upperBound.get("range"));
        Assertions.assertTrue(upperBound.get("required").asBoolean());
        Assertions.assertTrue(upperBound.get("default").isTextual());
        Assertions.assertEquals("0", upperBound.get("default").asText());

        JsonNode hardwareModel = settings.get(1);
        Assertions.assertEquals(Set.of("id", "type", "label", "description", "options", "required", "default"),
                names(hardwareModel));
        Assertions.assertEquals("hardwareModel", hardwareModel.get("id").asText());
        Assertions.assertEquals("select", hardwareModel.get("type").asText());
        Assertions.assertEquals("Hardware model", hardwareModel.get("label").asText());
        Assertions.assertEquals("unit", hardwareModel.get("options").get(0).get("id").asText());
        Assertions.assertEquals("Unit cost (every operation costs 1)",
                hardwareModel.get("options").get(0).get("label").asText());
        Assertions.assertEquals("huge", hardwareModel.get("options").get(1).get("id").asText());
        for (JsonNode option : hardwareModel.get("options")) {
            Assertions.assertEquals(Set.of("id", "label"), names(option));
        }
        Assertions.assertTrue(hardwareModel.get("required").asBoolean());
        Assertions.assertEquals("unit", hardwareModel.get("default").asText());

        JsonNode initialState = settings.get(2);
        Assertions.assertEquals(Set.of("id", "type", "label", "description", "required", "default"),
                names(initialState));
        Assertions.assertEquals("initialState", initialState.get("id").asText());
        Assertions.assertEquals("text", initialState.get("type").asText());
        Assertions.assertEquals("Initial variable values", initialState.get("label").asText());
        Assertions.assertFalse(initialState.get("required").asBoolean());
        Assertions.assertTrue(initialState.get("default").isTextual());
        Assertions.assertEquals("", initialState.get("default").asText());
        String help = initialState.get("description").asText();
        Assertions.assertTrue(help.contains("n=10, i=0") && help.contains("start at 0")
                && help.contains("not executed"), help);
    }

    @Test
    void lateStreamStillGetsTheReplayAndDone() throws Exception {
        String id = UUID.randomUUID().toString();
        client.start(id, VerifierClient.requestBody(program("unsupported"), settings("50", null, null)));
        Thread.sleep(1500); // the job is long finished when the stream opens
        VerifierClient.Stream stream = client.stream(id);
        Assertions.assertEquals(List.of(
                "Hardware model: unit, upper bound: 50, initial state: all 0",
                "Statement 'inc' (id 4): unsupported code: statement 'i++;' is not supported",
                "1 proven, 4 failed, 0 not executed"), stream.logLines());
        Assertions.assertEquals(1000, stream.closeCode());
        Assertions.assertEquals("total energy estimate unavailable", stream.done().get("status").asText());
        Assertions.assertEquals(5, client.result(id).size());
    }

    @Test
    void streamClosesWith1000AfterDone() {
        VerifierClient.Run run = client.run(program("loop"), settings("50", null, "n=3"));
        Assertions.assertEquals(1000, run.stream().closeCode());
        Assertions.assertEquals("done", run.stream().messages().getLast().get("type").asText());
    }

    @Test
    void resultCoversEveryStatementWithStringKeys() {
        VerifierClient.Run run = client.run(program("exponentiation"), settings("1000", null, "a=2, n=5"));
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = run.result().fieldNames(); it.hasNext(); ) {
            keys.add(it.next());
        }
        Assertions.assertEquals(List.of("1", "2", "3", "4", "5", "6"), keys);
        Assertions.assertTrue(run.proven());
    }

    @Test
    void concurrentJobsStayIndependent() throws Exception {
        int jobs = 12;
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        try {
            List<Future<VerifierClient.Run>> runs = new ArrayList<>();
            for (int n = 0; n < jobs; n++) {
                String initialState = "n=" + (n * 50);
                runs.add(pool.submit(() -> client.run(program("loop"), settings("100000", null, initialState))));
            }
            for (int n = 0; n < jobs; n++) {
                VerifierClient.Run run = runs.get(n).get();
                int total = 5 + 8 * n * 50;
                Assertions.assertEquals("total energy estimate: " + total + " / 100000", run.status());
                Assertions.assertEquals("Hardware model: unit, upper bound: 100000, initial state: n=" + (n * 50),
                        run.log().getFirst());
                Assertions.assertEquals(n == 0 ? "not executed for this input" : "energy estimate: max 4 / 100000",
                        run.result().get("4").get("status").asText());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
