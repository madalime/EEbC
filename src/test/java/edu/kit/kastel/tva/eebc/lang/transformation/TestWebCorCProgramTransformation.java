package edu.kit.kastel.tva.eebc.lang.transformation;

import de.tu_bs.cs.isf.cbc.cbcmodel.AbstractStatement;
import de.tu_bs.cs.isf.cbc.cbcmodel.CbCFormula;
import de.tu_bs.cs.isf.cbc.cbcmodel.CompositionStatement;
import de.tu_bs.cs.isf.cbc.cbcmodel.SelectionStatement;
import de.tu_bs.cs.isf.cbc.cbcmodel.SmallRepetitionStatement;
import edu.kit.kastel.tva.eebc.lang.ast.BinOp;
import edu.kit.kastel.tva.eebc.lang.ast.Const;
import edu.kit.kastel.tva.eebc.lang.ast.IfThenElse;
import edu.kit.kastel.tva.eebc.lang.ast.Repeat;
import edu.kit.kastel.tva.eebc.lang.ast.Skip;
import edu.kit.kastel.tva.eebc.lang.ast.Statement;
import edu.kit.kastel.tva.eebc.lang.ast.StatementConcat;
import edu.kit.kastel.tva.eebc.lang.ast.VarAssignment;
import edu.kit.kastel.tva.eebc.lang.transformation.ProgramStatement.Type;
import edu.kit.kastel.tva.eebc.typesystem.ComponentState;
import edu.kit.kastel.tva.eebc.typesystem.ProgramState;
import edu.kit.kastel.tva.eebc.typesystem.State;
import edu.kit.kastel.tva.eebc.typesystem.StatementType;
import edu.kit.kastel.tva.eebc.typesystem.StatementType.TypingState;
import edu.kit.kastel.tva.eebc.typesystem.TestHardwareModel;
import edu.kit.kastel.tva.eebc.typesystem.TypingVisitor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class TestWebCorCProgramTransformation {

    // ---- reading the WebCorC program syntax ----

    @Test
    public void testReadExponentiationProgram() throws IOException {
        WebCorCProgram program = CbCModelReader.readProgram(resource("Exponentiation.json"));

        Assertions.assertEquals("Exponentiation", program.formula().getName());
        Assertions.assertEquals(List.of("int a", "int n", "int i", "int b", "int z"), program.javaVariables());
        Assertions.assertEquals(List.of("a", "n", "i", "b", "z"), program.variableNames());
        Assertions.assertEquals(1, program.rootId());

        Assertions.assertEquals(List.of(1, 2, 3, 4, 5, 6), List.copyOf(program.statements().keySet()));
        Assertions.assertEquals(new ProgramStatement(1, "compositionStatement", Type.COMPOSITION, null, List.of(2, 3)),
                program.statement(1));
        Assertions.assertEquals(new ProgramStatement(2, "init", Type.STATEMENT, 1, List.of()), program.statement(2));
        Assertions.assertEquals(new ProgramStatement(3, "loop", Type.REPETITION, 1, List.of(4)), program.statement(3));
        Assertions.assertEquals(new ProgramStatement(4, "selection", Type.SELECTION, 3, List.of(5, 6)),
                program.statement(4));
        Assertions.assertEquals(new ProgramStatement(6, "odd", Type.STATEMENT, 4, List.of()), program.statement(6));

        // ids and names are carried onto the cbcmodel objects (wrapper + refinement, as in CorC XMI)
        AbstractStatement root = program.formula().getStatement();
        Assertions.assertEquals("1", root.getId());
        Assertions.assertEquals("compositionStatement", root.getName());
        CompositionStatement composition = (CompositionStatement) root.getRefinement();
        Assertions.assertEquals("1", composition.getId());
        AbstractStatement init = composition.getFirstStatement();
        Assertions.assertEquals("2", init.getId());
        Assertions.assertEquals("init", init.getName());
        Assertions.assertEquals("i = n-1; b = a; z = a;", init.getRefinement().getName());
        SmallRepetitionStatement loop = (SmallRepetitionStatement) composition.getSecondStatement().getRefinement();
        Assertions.assertEquals("3", loop.getId());
        Assertions.assertEquals("i != 0", loop.getGuard().getName());
        Assertions.assertEquals("i", loop.getVariant().getName());
        Assertions.assertEquals("z * pow(b,i) = pow(a,n)", loop.getInvariant().getName());
        SelectionStatement selection = (SelectionStatement) loop.getLoopStatement().getRefinement();
        Assertions.assertEquals(List.of("i%2 == 0", "i%2 == 1"),
                selection.getGuards().stream().map(c -> c.getName()).toList());
        Assertions.assertEquals("5", selection.getCommands().getFirst().getId());
    }

    @Test
    public void testReadModelStringAcceptsProgramJson() throws Exception {
        CbCFormula formula = CbCModelReader.readModelString(resource("Exponentiation.json"));
        Assertions.assertEquals("Exponentiation", formula.getName());
        Assertions.assertEquals("1", formula.getStatement().getId());
    }

    @Test
    public void testJsonAndXmiModelComputeTheSame() throws Exception {
        CbCFormula json = CbCModelReader.readModelString(resource("Exponentiation.json"));
        CbCFormula xmi = CbCModelReader.readModelString(resource("Exponentiation.cbcmodel"));
        State before = state(Map.of("a", 2, "n", 5));

        StatementType jsonType = type(Transformer.transform(json));
        StatementType xmiType = type(Transformer.transform(xmi));

        Assertions.assertEquals(32, jsonType.s(before).programState().getVariable("z"), "2^5");
        Assertions.assertEquals(xmiType.s(before), jsonType.s(before));
        Assertions.assertEquals(xmiType.e(before), jsonType.e(before));
        Assertions.assertEquals(TypingState.COMPLETE, jsonType.typingState(before));
    }

    @Test
    public void testAncestorsAndRunsAfter() throws IOException {
        WebCorCProgram program = CbCModelReader.readProgram(resource("Exponentiation.json"));

        Assertions.assertEquals(List.of(4, 3, 1), program.ancestors(5));
        Assertions.assertEquals(List.of(), program.ancestors(1));
        Assertions.assertEquals(List.of(4, 5, 6), program.descendants(3));
        Assertions.assertEquals(List.of(3, 4, 5, 6), program.runsAfter(2));
        // in the next loop iteration, the other branch may run
        Assertions.assertEquals(List.of(6), program.runsAfter(5));
        Assertions.assertEquals(List.of(), program.runsAfter(3));
        Assertions.assertEquals(List.of(), program.runsAfter(1));
    }

    // ---- statement-id-aware translation ----

    @Test
    public void testIdToNodeMap() throws IOException {
        WebCorCProgram program = CbCModelReader.readProgram(resource("Exponentiation.json"));
        TransformationResult result = Transformer.transformWithIds(program.formula());

        Assertions.assertEquals(Map.of(), result.failures());
        Assertions.assertEquals(program.statements().keySet(), result.nodes().keySet());

        Statement root = result.nodes().get(1);
        Assertions.assertSame(root, result.root());
        StatementConcat composition = Assertions.assertInstanceOf(StatementConcat.class, root);
        Assertions.assertSame(result.nodes().get(2), composition.getLeft());
        Repeat loop = Assertions.assertInstanceOf(Repeat.class, result.nodes().get(3));
        Assertions.assertSame(loop, composition.getRight());
        IfThenElse loopBody = Assertions.assertInstanceOf(IfThenElse.class, loop.getBody());
        Assertions.assertInstanceOf(Skip.class, loopBody.getElseBranch());
        IfThenElse selection = Assertions.assertInstanceOf(IfThenElse.class, result.nodes().get(4));
        Assertions.assertSame(selection, loopBody.getThenBranch());
        Assertions.assertSame(result.nodes().get(5), selection.getThenBranch());
        IfThenElse secondBranch = Assertions.assertInstanceOf(IfThenElse.class, selection.getElseBranch());
        Assertions.assertSame(result.nodes().get(6), secondBranch.getThenBranch());
        Assertions.assertInstanceOf(Skip.class, secondBranch.getElseBranch());

        // same AST shape as the legacy entry point
        State before = state(Map.of("a", 3, "n", 4));
        StatementType legacy = type(Transformer.transform(program.formula()));
        StatementType withIds = type(result.root());
        Assertions.assertEquals(legacy.s(before), withIds.s(before));
        Assertions.assertEquals(legacy.e(before), withIds.e(before));
    }

    @Test
    public void testMissingVariant() throws IOException {
        TransformationResult result = transformWithIds("""
                { "name": "p", "javaVariables": ["int i"], "statement":
                  { "id": 10, "name": "outer", "type": "COMPOSITION",
                    "firstStatement": { "id": 11, "name": "loop", "type": "REPETITION",
                      "guard": { "condition": "i > 0" },
                      "invariant": { "condition": "i >= 0" },
                      "loopStatement": { "id": 12, "name": "dec", "type": "STATEMENT",
                                         "programStatement": "i = i - 1;" } },
                    "secondStatement": { "id": 13, "name": "after", "type": "STATEMENT",
                                         "programStatement": "i = 7;" } } }
                """);

        Assertions.assertEquals(Map.of(11, "loop has no variant"), result.failures());
        Assertions.assertEquals(List.of(10, 11, 12, 13), result.nodes().keySet().stream().sorted().toList());
        Repeat loop = Assertions.assertInstanceOf(Repeat.class, result.nodes().get(11));
        Assertions.assertEquals(0, Assertions.assertInstanceOf(Const.class, loop.getCount()).getValue());
        Assertions.assertSame(result.nodes().get(12),
                ((IfThenElse) loop.getBody()).getThenBranch());
        Assertions.assertInstanceOf(VarAssignment.class, result.nodes().get(13));

        // the placeholder tree can still be typed and evaluated
        State before = state(Map.of("i", 3));
        Assertions.assertEquals(7, type(result.root()).s(before).programState().getVariable("i"));
    }

    @Test
    public void testBlankVariantCountsAsMissing() throws IOException {
        TransformationResult result = transformWithIds("""
                { "name": "p", "javaVariables": [], "statement":
                  { "id": 1, "name": "loop", "type": "REPETITION", "guard": { "condition": "i > 0" },
                    "variant": { "condition": "  " },
                    "loopStatement": { "id": 2, "name": "s", "type": "SKIP" } } }
                """);
        Assertions.assertEquals(Map.of(1, "loop has no variant"), result.failures());
    }

    @Test
    public void testUnsupportedCode() throws IOException {
        TransformationResult result = transformWithIds("""
                { "name": "p", "javaVariables": ["int i", "int x"], "statement":
                  { "id": 1, "name": "outer", "type": "COMPOSITION",
                    "firstStatement": { "id": 2, "name": "inc", "type": "STATEMENT", "programStatement": "i++;" },
                    "secondStatement": { "id": 3, "name": "rest", "type": "COMPOSITION",
                      "firstStatement": { "id": 4, "name": "if", "type": "SELECTION",
                        "guards": [ { "condition": "f(i) > 0" }, { "condition": "i > 0" } ],
                        "commands": [ { "id": 5, "name": "a", "type": "STATEMENT", "programStatement": "x = 1;" },
                                      { "id": 6, "name": "b", "type": "STATEMENT", "programStatement": "x += 1;" } ] },
                      "secondStatement": { "id": 7, "name": "c", "type": "STATEMENT",
                                           "programStatement": "x = (x + 1);" } } } }
                """);

        Assertions.assertEquals(Map.of(
                2, "statement 'i++;' is not supported",
                4, "expression 'f(i)' is not supported",
                6, "statement 'x += 1;' is not supported",
                7, "expression '(x + 1)' is not supported"
        ), result.failures());
        Assertions.assertInstanceOf(Skip.class, result.nodes().get(2));
        Assertions.assertInstanceOf(VarAssignment.class, result.nodes().get(5));
        Assertions.assertInstanceOf(Skip.class, result.nodes().get(6));
        Assertions.assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), result.nodes().keySet().stream().sorted().toList());
        IfThenElse selection = (IfThenElse) result.nodes().get(4);
        Assertions.assertEquals(0, Assertions.assertInstanceOf(Const.class, selection.getCondition()).getValue());
    }

    @Test
    public void testSyntaxError() throws IOException {
        TransformationResult result = transformWithIds("""
                { "name": "p", "javaVariables": [], "statement":
                  { "id": 1, "name": "broken", "type": "STATEMENT", "programStatement": "i = ;" } }
                """);
        Assertions.assertEquals(Map.of(1, "syntax error in 'i = ;'"), result.failures());
        Assertions.assertSame(result.root(), result.nodes().get(1));
    }

    @Test
    public void testLegacyEntryPointStillThrows() throws IOException {
        WebCorCProgram program = CbCModelReader.readProgram("""
                { "name": "p", "javaVariables": [], "statement":
                  { "id": 1, "name": "inc", "type": "STATEMENT", "programStatement": "i++;" } }
                """);
        Assertions.assertThrows(IllegalArgumentException.class, () -> Transformer.transform(program.formula()));
    }

    @Test
    public void testEmptyStatementIsIncomplete() throws IOException {
        TransformationResult result = transformWithIds("""
                { "name": "p", "javaVariables": ["int i"], "statement":
                  { "id": 1, "name": "outer", "type": "COMPOSITION",
                    "firstStatement": { "id": 2, "name": "todo", "type": "STATEMENT", "programStatement": "" },
                    "secondStatement": { "id": 3, "name": "set", "type": "STATEMENT",
                                         "programStatement": "i = 5;" } } }
                """);

        Assertions.assertEquals(Map.of(), result.failures());
        StatementConcat hole = Assertions.assertInstanceOf(StatementConcat.class, result.nodes().get(2));
        Assertions.assertNull(hole.getLeft());
        Assertions.assertNull(hole.getRight());

        State before = state(Map.of());
        StatementType holeType = type(hole);
        Assertions.assertEquals(TypingState.INCOMPLETE, holeType.typingState(before));
        Assertions.assertEquals(0, holeType.e(before));
        Assertions.assertEquals(before, holeType.s(before));

        StatementType rootType = type(result.root());
        Assertions.assertEquals(TypingState.INCOMPLETE, rootType.typingState(before));
        Assertions.assertEquals(5, rootType.s(before).programState().getVariable("i"));
        Assertions.assertEquals(TypingState.COMPLETE, type(result.nodes().get(3)).typingState(before));
    }

    @Test
    public void testSkipIsNotUnrefined() throws IOException {
        TransformationResult result = transformWithIds("""
                { "name": "p", "javaVariables": [], "statement": { "id": 1, "name": "s", "type": "SKIP" } }
                """);
        Assertions.assertInstanceOf(Skip.class, result.root());
        Assertions.assertEquals(TypingState.COMPLETE, type(result.root()).typingState(state(Map.of())));
    }

    // ---- structural errors ----

    static Stream<Arguments> structuralErrors() {
        return Stream.of(
                Arguments.of("unknown type", """
                        { "id": 1, "name": "root", "type": "COMPOSITION",
                          "firstStatement": { "id": 2, "name": "weird", "type": "LOOP" },
                          "secondStatement": { "id": 3, "name": "s", "type": "SKIP" } }
                        """, 2, "weird", "Statement 'weird' (id 2): unknown type 'LOOP'"),
                Arguments.of("missing child", """
                        { "id": 1, "name": "root", "type": "COMPOSITION",
                          "firstStatement": { "id": 2, "name": "s", "type": "SKIP" } }
                        """, 1, "root", "Statement 'root' (id 1): missing 'secondStatement'"),
                Arguments.of("missing loop body", """
                        { "id": 4, "name": "loop", "type": "REPETITION", "guard": { "condition": "i > 0" },
                          "variant": { "condition": "i" } }
                        """, 4, "loop", "Statement 'loop' (id 4): missing 'loopStatement'"),
                Arguments.of("missing guard", """
                        { "id": 4, "name": "loop", "type": "REPETITION", "variant": { "condition": "i" },
                          "loopStatement": { "id": 5, "name": "s", "type": "SKIP" } }
                        """, 4, "loop", "Statement 'loop' (id 4): missing condition in 'guard'"),
                Arguments.of("guards/commands mismatch", """
                        { "id": 7, "name": "if", "type": "SELECTION",
                          "guards": [ { "condition": "i > 0" }, { "condition": "i < 0" } ],
                          "commands": [ { "id": 8, "name": "s", "type": "SKIP" } ] }
                        """, 7, "if", "Statement 'if' (id 7): selection has 2 guards but 1 commands"),
                Arguments.of("duplicate id", """
                        { "id": 1, "name": "root", "type": "COMPOSITION",
                          "firstStatement": { "id": 2, "name": "a", "type": "SKIP" },
                          "secondStatement": { "id": 2, "name": "b", "type": "SKIP" } }
                        """, 2, "b", "Statement 'b' (id 2): duplicate id 2"),
                Arguments.of("missing id", """
                        { "name": "anonymous", "type": "SKIP" }
                        """, null, "anonymous", "Statement 'anonymous': missing or non-integer 'id'")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structuralErrors")
    public void testStructuralErrors(String description, String statement, Integer expId, String expName,
                                     String expMessage) {
        String json = "{ \"name\": \"p\", \"javaVariables\": [], \"statement\": " + statement + " }";
        InvalidProgramException e = Assertions.assertThrows(InvalidProgramException.class,
                () -> CbCModelReader.readProgram(json));
        Assertions.assertEquals(expId, e.statementId());
        Assertions.assertEquals(expName, e.statementName());
        Assertions.assertEquals(expMessage, e.getMessage());
    }

    @Test
    public void testMissingRootStatement() {
        InvalidProgramException e = Assertions.assertThrows(InvalidProgramException.class,
                () -> CbCModelReader.readProgram("{ \"name\": \"p\", \"javaVariables\": [] }"));
        Assertions.assertNull(e.statementId());
        Assertions.assertEquals("Program: missing 'statement'", e.getMessage());
    }

    // ---- helpers ----

    private static TransformationResult transformWithIds(String json) throws IOException {
        return Transformer.transformWithIds(CbCModelReader.readProgram(json).formula());
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = TestWebCorCProgramTransformation.class.getResourceAsStream(name)) {
            Assertions.assertNotNull(stream, "missing test resource " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static State state(Map<String, Integer> variables) {
        ProgramState programState = new ProgramState();
        variables.forEach(programState::setVariable);
        return new State(programState, ComponentState.empty());
    }

    /** Types a statement with the demo's unit-cost hardware model. */
    private static StatementType type(Statement statement) {
        TestHardwareModel hardware = new TestHardwareModel();
        hardware.setTimeConst(1);
        hardware.setTimeVar(1);
        hardware.setTimeVarAssignment(1);
        hardware.setTimeIf(1);
        for (BinOp.Op op : BinOp.Op.values()) {
            hardware.putTimeBinOp(op, 1);
        }
        return statement.accept(new TypingVisitor(hardware));
    }
}
