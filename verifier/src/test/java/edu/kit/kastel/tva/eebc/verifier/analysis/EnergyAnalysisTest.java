package edu.kit.kastel.tva.eebc.verifier.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.kit.kastel.tva.eebc.lang.ast.Statement;
import edu.kit.kastel.tva.eebc.lang.transformation.CbCModelReader;
import edu.kit.kastel.tva.eebc.lang.transformation.Transformer;
import edu.kit.kastel.tva.eebc.lang.transformation.WebCorCProgram;
import edu.kit.kastel.tva.eebc.typesystem.ComponentState;
import edu.kit.kastel.tva.eebc.typesystem.ProgramState;
import edu.kit.kastel.tva.eebc.typesystem.State;
import edu.kit.kastel.tva.eebc.typesystem.TypingVisitor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static edu.kit.kastel.tva.eebc.verifier.Fixtures.MAPPER;
import static edu.kit.kastel.tva.eebc.verifier.Fixtures.costEverything;
import static edu.kit.kastel.tva.eebc.verifier.Fixtures.program;
import static edu.kit.kastel.tva.eebc.verifier.Fixtures.settings;

/** Seam 2: the analysis entry point {@code (program, settings) → per-statement results, log lines, done}. */
class EnergyAnalysisTest {
    private static final Pattern TOTAL = Pattern.compile("total energy estimate: (-?\\d+) / (\\d+)");

    // ---- consistency with the EEbC demo ----

    static Stream<Arguments> demoRuns() {
        return Stream.of(
                Arguments.of("straight", "", false, 6),
                Arguments.of("branch", "x=5", false, 6),
                Arguments.of("branch", "x=0", false, 12),
                Arguments.of("loop", "n=0", false, 5),
                Arguments.of("loop", "n=1", false, 13),
                Arguments.of("loop", "n=3", false, 29),
                Arguments.of("loopSelection", "n=3", false, 57),
                Arguments.of("loopSelection", "n=6", false, -1),
                Arguments.of("exponentiation", "a=2, n=5", false, -1),
                Arguments.of("exponentiation", "a=3, n=10", false, -1),
                Arguments.of("unrefined", "", true, 2),
                Arguments.of("unrefinedLoop", "n=2", true, 13)
        );
    }

    /**
     * The root's energy equals a plain {@link TypingVisitor} run with the unit model from the same start state
     * (the EEbC demo), which shows the layer only observes EEbC. Where given, it also equals the hand-computed value.
     */
    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("demoRuns")
    void rootEnergyEqualsDemo(String fixture, String initialState, boolean hasHoles, int handComputed)
            throws Exception {
        JsonNode json = program(fixture);
        AnalysisReport report = EnergyAnalysis.prepare(json, settings("1000000", null, initialState)).run();

        WebCorCProgram program = CbCModelReader.readProgram(json);
        // programs with unrefined holes are only translatable by the id-aware entry point
        Statement ast = hasHoles ? Transformer.transformWithIds(program.formula()).root()
                : Transformer.transform(program.formula());
        ProgramState programState = new ProgramState();
        AnalysisSettings.parseInitialState(initialState).forEach(programState::setVariable);
        int demo = ast.accept(new TypingVisitor(costEverything(1)))
                .e(new State(programState, ComponentState.empty()));

        Matcher total = TOTAL.matcher(report.status());
        Assertions.assertTrue(total.matches(), report.status());
        Assertions.assertEquals(demo, Integer.parseInt(total.group(1)));
        Assertions.assertEquals("energy estimate" + (hasHoles ? " incomplete: " : ": ") + demo + " / 1000000"
                        + (hasHoles ? " (contains unrefined statements)" : ""),
                report.statements().get(program.rootId()).status());
        if (handComputed >= 0) {
            Assertions.assertEquals(handComputed, demo);
        }
        Assertions.assertEquals(program.statements().keySet(), report.statements().keySet());
    }

    @Test
    void sameInputGivesSameReport() {
        JsonNode json = program("loopSelection");
        ObjectNode settings = settings("30", null, "n=5");
        Assertions.assertEquals(EnergyAnalysis.prepare(json, settings).run(),
                EnergyAnalysis.prepare(json, settings).run());
    }

    // ---- console log ----

    @Test
    void logHasOpeningLineAndSummary() {
        AnalysisReport report = EnergyAnalysis.prepare(program("straight"), settings("50", "unit", "")).run();
        Assertions.assertEquals(List.of(
                "Hardware model: unit, upper bound: 50, initial state: all 0",
                "3 proven, 0 failed, 0 not executed"), report.log());
        Assertions.assertTrue(report.proven());
        Assertions.assertEquals("total energy estimate: 6 / 50", report.status());
    }

    @Test
    void missingSettingsTakeTheirDefaults() {
        AnalysisReport report = EnergyAnalysis.prepare(program("straight"), MAPPER.createObjectNode()).run();
        Assertions.assertEquals("Hardware model: unit, upper bound: 0, initial state: all 0", report.log().getFirst());
        Assertions.assertEquals(new StatementResult(false, "energy estimate: 6 / 0 (exceeds bound)"),
                report.statements().get(1));
    }

    @Test
    void unknownSettingsAreIgnored() {
        ObjectNode settings = settings("50", null, null);
        settings.put("verbose", true);
        settings.put("somethingNew", "x");
        Assertions.assertTrue(EnergyAnalysis.prepare(program("straight"), settings).run().proven());
    }

    @Test
    void unknownVariableInInitialStateIsAWarning() {
        AnalysisReport report = EnergyAnalysis.prepare(program("loop"), settings("50", null, "n=3, q=1")).run();
        Assertions.assertEquals(List.of(
                "Hardware model: unit, upper bound: 50, initial state: n=3, q=1",
                "Initial state: 'q' is not a program variable",
                "4 proven, 0 failed, 0 not executed"), report.log());
        Assertions.assertEquals("total energy estimate: 29 / 50", report.status());
    }

    @Test
    void consoleReceivesTheLinesAsTheyAreProduced() {
        List<String> seen = new java.util.ArrayList<>();
        AnalysisReport report = EnergyAnalysis.prepare(program("unsupported"), settings("50", null, "")).run(seen::add);
        Assertions.assertEquals(report.log(), seen);
        Assertions.assertEquals(List.of(
                "Hardware model: unit, upper bound: 50, initial state: all 0",
                "Statement 'inc' (id 4): unsupported code: statement 'i++;' is not supported",
                "1 proven, 4 failed, 0 not executed"), seen);
    }

    // ---- initialState grammar ----

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "\"\"                               | all 0",
            "\"   \"                            | all 0",
            "n=10                               | n=10",
            "\" n = 10 , i=0 \"                 | n=10, i=0",
            "n=10, i=0,                         | n=10, i=0",
            "\"n=10, i=0 , \"                   | n=10, i=0",
            "n=-5                               | n=-5",
            "n=+3                               | n=3",
            "_x1=2147483647, Y_2=-2147483648    | _x1=2147483647, Y_2=-2147483648",
            "n=007                              | n=7",
    })
    void validInitialState(String initialState, String described) {
        AnalysisReport report = EnergyAnalysis.prepare(program("loop"), settings("50", null, initialState)).run();
        Assertions.assertEquals("Hardware model: unit, upper bound: 50, initial state: " + described,
                report.log().getFirst());
    }

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "n==10              | Initial state: expected name=integer at 'n==10'",
            "n                  | Initial state: expected name=integer at 'n'",
            "n=                 | Initial state: expected name=integer at 'n='",
            "=5                 | Initial state: expected name=integer at '=5'",
            "1n=2               | Initial state: expected name=integer at '1n=2'",
            "n=10 i=0           | Initial state: expected name=integer at 'n=10 i=0'",
            "n=10; i=0          | Initial state: expected name=integer at 'n=10; i=0'",
            "n=1, i=x           | Initial state: expected name=integer at 'i=x'",
            "n=1.5              | Initial state: expected name=integer at 'n=1.5'",
            "n=99999999999      | Initial state: value of 'n' is not a 32-bit integer at 'n=99999999999'",
            "n=2147483648       | Initial state: value of 'n' is not a 32-bit integer at 'n=2147483648'",
            "n=1,,i=2           | Initial state: empty entry between commas",
            ",n=1               | Initial state: empty entry between commas",
            "n=1,,              | Initial state: empty entry between commas",
            ",                  | Initial state: empty entry between commas",
            "n=1, n=2           | Initial state: 'n' is given twice",
    })
    void invalidInitialState(String initialState, String message) {
        InvalidRequestException e = Assertions.assertThrows(InvalidRequestException.class,
                () -> EnergyAnalysis.prepare(program("loop"), settings("50", null, initialState)));
        Assertions.assertEquals(message, e.getMessage());
    }

    // ---- upperBound ----

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "0                     | 0",
            "50                    | 50",
            "\" 7 \"               | 7",
            "12.0                  | 12",
            "12.                   | 12",
            "+3                    | 3",
            "9223372036854775807   | 9223372036854775807",
    })
    void validUpperBound(String upperBound, long expected) {
        EnergyAnalysis analysis = EnergyAnalysis.prepare(program("straight"), settings(upperBound, null, null));
        Assertions.assertEquals(expected, analysis.settings().upperBound());
        Assertions.assertEquals("Hardware model: unit, upper bound: " + expected + ", initial state: all 0",
                analysis.run().log().getFirst());
    }

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "-1                    | Energy upper bound must be a whole number ≥ 0, got '-1'",
            "1.5                   | Energy upper bound must be a whole number ≥ 0, got '1.5'",
            "abc                   | Energy upper bound must be a whole number ≥ 0, got 'abc'",
            "\"\"                  | Energy upper bound must be a whole number ≥ 0, got ''",
            "1e3                   | Energy upper bound must be a whole number ≥ 0, got '1e3'",
            "1,5                   | Energy upper bound must be a whole number ≥ 0, got '1,5'",
            "9223372036854775808   | Energy upper bound is too large, got '9223372036854775808'",
    })
    void invalidUpperBound(String upperBound, String message) {
        InvalidRequestException e = Assertions.assertThrows(InvalidRequestException.class,
                () -> EnergyAnalysis.prepare(program("straight"), settings(upperBound, null, null)));
        Assertions.assertEquals(message, e.getMessage());
    }

    @Test
    void upperBoundMayBeAJsonNumber() {
        ObjectNode settings = MAPPER.createObjectNode().put("upperBound", 50);
        Assertions.assertEquals(50, EnergyAnalysis.prepare(program("straight"), settings).settings().upperBound());
    }

    // ---- other settings and program errors ----

    @Test
    void unknownHardwareModel() {
        InvalidRequestException e = Assertions.assertThrows(InvalidRequestException.class,
                () -> EnergyAnalysis.prepare(program("straight"), settings("5", "fast", null)));
        Assertions.assertEquals("Unknown hardware model 'fast' (available: unit)", e.getMessage());
    }

    @Test
    void nonStringSetting() {
        ObjectNode settings = MAPPER.createObjectNode();
        settings.putArray("initialState");
        InvalidRequestException e = Assertions.assertThrows(InvalidRequestException.class,
                () -> EnergyAnalysis.prepare(program("straight"), settings));
        Assertions.assertEquals("Setting 'initialState' must be a string", e.getMessage());
    }

    @Test
    void structuralProgramError() throws Exception {
        JsonNode json = MAPPER.readTree("""
                { "name": "p", "javaVariables": [], "statement":
                  { "id": 7, "name": "if", "type": "SELECTION",
                    "guards": [ { "condition": "i > 0" }, { "condition": "i < 0" } ],
                    "commands": [ { "id": 8, "name": "s", "type": "SKIP" } ] } }
                """);
        InvalidRequestException e = Assertions.assertThrows(InvalidRequestException.class,
                () -> EnergyAnalysis.prepare(json, settings("5", null, null)));
        Assertions.assertEquals("Statement 'if' (id 7): selection has 2 guards but 1 commands", e.getMessage());
    }

    @Test
    void overflowWithACostlyHardwareModel() {
        AnalysisReport report = EnergyAnalysis.prepare(program("twoAssignments"), settings("3000000000", "huge", ""),
                edu.kit.kastel.tva.eebc.verifier.Fixtures.registryWithHugeModel()).run();
        Assertions.assertEquals(Map.of(
                1, new StatementResult(false, "energy estimate overflowed"),
                2, new StatementResult(true, "energy estimate: 2000000000 / 3000000000"),
                3, new StatementResult(true, "energy estimate: 2000000000 / 3000000000")), report.statements());
        Assertions.assertFalse(report.proven());
        Assertions.assertEquals("total energy estimate unavailable", report.status());
    }
}
