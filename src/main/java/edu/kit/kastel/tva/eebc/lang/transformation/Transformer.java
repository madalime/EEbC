package edu.kit.kastel.tva.eebc.lang.transformation;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import de.tu_bs.cs.isf.cbc.cbcmodel.AbstractStatement;
import de.tu_bs.cs.isf.cbc.cbcmodel.CbCFormula;
import de.tu_bs.cs.isf.cbc.cbcmodel.CompositionStatement;
import de.tu_bs.cs.isf.cbc.cbcmodel.Condition;
import de.tu_bs.cs.isf.cbc.cbcmodel.SelectionStatement;
import de.tu_bs.cs.isf.cbc.cbcmodel.SkipStatement;
import de.tu_bs.cs.isf.cbc.cbcmodel.SmallRepetitionStatement;
import edu.kit.kastel.tva.eebc.lang.ast.BinOp;
import edu.kit.kastel.tva.eebc.lang.ast.Const;
import edu.kit.kastel.tva.eebc.lang.ast.Expression;
import edu.kit.kastel.tva.eebc.lang.ast.IfThenElse;
import edu.kit.kastel.tva.eebc.lang.ast.Repeat;
import edu.kit.kastel.tva.eebc.lang.ast.Skip;
import edu.kit.kastel.tva.eebc.lang.ast.Statement;
import edu.kit.kastel.tva.eebc.lang.ast.StatementConcat;
import edu.kit.kastel.tva.eebc.lang.ast.Var;
import edu.kit.kastel.tva.eebc.lang.ast.VarAssignment;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Translates CbC models into the EEbC AST. All translation rules live here:
 * composition &rarr; {@link StatementConcat}; selection &rarr; an {@link IfThenElse} chain ending in {@link Skip};
 * repetition &rarr; {@code Repeat(variant, IfThenElse(guard, body, Skip))}; a leaf &rarr; its parsed Java block.
 * The supported Java subset is plain assignments {@code x = e;} where {@code e} consists of variables, integer
 * literals and binary operators.
 * <p>
 * Instances are not shared: every call uses its own parser, so the static methods are thread-safe.
 */
public class Transformer {
    private final JavaParser javaParser = new JavaParser();
    /** {@code false}: throw on the first problem (legacy behaviour). */
    private final boolean tolerant;
    private final Map<Integer, Statement> nodes = new HashMap<>();
    private final Map<Integer, String> failures = new HashMap<>();
    /** Integer ids of the statements currently being translated, innermost first. */
    private final Deque<Integer> ids = new ArrayDeque<>();

    private Transformer(boolean tolerant) {
        this.tolerant = tolerant;
    }

    /**
     * Translates a formula, throwing on the first problem.
     *
     * @throws IllegalArgumentException if a statement, guard or variant cannot be translated
     */
    public static Statement transform(CbCFormula formula) {
        return new Transformer(false).statement(formula.getStatement());
    }

    /**
     * Translates a formula without stopping at the first problem, and maps statement ids to AST nodes.
     * <p>
     * Statement ids are read from {@link AbstractStatement#getId()}; ids that are not decimal integers are ignored
     * (their statements are translated but not listed). Problems are handled like this:
     * <ul>
     *     <li>A leaf whose code cannot be parsed or is outside the supported Java subset: the reason is recorded
     *     and the leaf becomes a {@link Skip} placeholder.</li>
     *     <li>A guard or variant that cannot be translated: the reason is recorded for the enclosing
     *     selection/repetition and the expression becomes the placeholder {@code Const(0)}.</li>
     *     <li>A repetition without variant: {@code loop has no variant} is recorded and the placeholder
     *     {@code Const(0)} is used as the iteration count.</li>
     *     <li>A non-skip leaf with blank code (not yet refined) becomes EEbC's unrefined form
     *     {@code StatementConcat(null, null)}, which {@code TypingVisitor} types as {@code INCOMPLETE}. This is not
     *     a failure.</li>
     * </ul>
     * A problem is recorded for the innermost statement with an integer id; if there is none, it is thrown.
     *
     * @throws IllegalArgumentException for a problem outside any statement with an integer id, or for a model the
     *                                  translation cannot handle at all (e.g. a selection without guards)
     */
    public static TransformationResult transformWithIds(CbCFormula formula) {
        Transformer transformer = new Transformer(true);
        Statement root = transformer.statement(formula.getStatement());
        return new TransformationResult(root, transformer.nodes, transformer.failures);
    }

    private Statement statement(AbstractStatement abstractStatement) {
        Integer id = tolerant ? idOf(abstractStatement) : null;
        if (id != null) {
            ids.push(id);
        }
        try {
            Statement node = switch (abstractStatement) {
                case CompositionStatement compositionStatement -> composition(compositionStatement);
                case SelectionStatement selectionStatement -> selection(selectionStatement);
                case SmallRepetitionStatement repetitionStatement -> repetition(repetitionStatement);
                default -> {
                    AbstractStatement refinement = abstractStatement.getRefinement();
                    yield refinement == null ? leaf(abstractStatement) : statement(refinement);
                }
            };
            if (id != null) {
                nodes.put(id, node);
            }
            return node;
        } finally {
            if (id != null) {
                ids.pop();
            }
        }
    }

    private static Integer idOf(AbstractStatement statement) {
        String id = statement.getId();
        if (id == null || !id.matches("-?\\d{1,10}")) {
            return null;
        }
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Statement leaf(AbstractStatement leaf) {
        String code = leaf.getName();
        if (tolerant && !(leaf instanceof SkipStatement) && (code == null || code.isBlank())) {
            return new StatementConcat(null, null);
        }
        return attempt(() -> parseBlock(code), Skip::new);
    }

    private StatementConcat composition(CompositionStatement compositionStatement) {
        Statement first = statement(compositionStatement.getFirstStatement());
        Statement second = statement(compositionStatement.getSecondStatement());
        return new StatementConcat(first, second);
    }

    private IfThenElse selection(SelectionStatement selectionStatement) {
        List<Condition> guards = selectionStatement.getGuards();
        List<AbstractStatement> commands = selectionStatement.getCommands();

        if (guards.isEmpty()) {
            throw new IllegalArgumentException("SelectionStatement must have at least one guard.");
        }

        IfThenElse first = new IfThenElse(condition(guards.getFirst()), statement(commands.getFirst()), null);
        IfThenElse result = first;

        for (int i = 1; i < guards.size(); i++) {
            IfThenElse newBranch = new IfThenElse(condition(guards.get(i)), statement(commands.get(i)), null);
            result.setElseBranch(newBranch);
            result = newBranch;
        }

        result.setElseBranch(new Skip());
        return first;
    }

    private Repeat repetition(SmallRepetitionStatement repetitionStatement) {
        Expression count;
        if (repetitionStatement.getVariant() == null) {
            if (!tolerant) {
                throw new UnsupportedCodeException("loop has no variant");
            }
            fail("loop has no variant");
            count = new Const(0);
        } else {
            count = attempt(() -> parseExpression(repetitionStatement.getVariant().getName()), () -> new Const(0));
        }
        return new Repeat(
                count,
                new IfThenElse(
                        condition(repetitionStatement.getGuard()),
                        statement(repetitionStatement.getLoopStatement()),
                        new Skip()
                )
        );
    }

    private Expression condition(Condition condition) {
        return attempt(() -> parseExpression(condition.getName()), () -> new Const(0));
    }

    /** Runs a translation step; in tolerant mode an unsupported-code problem is recorded and replaced. */
    private <T> T attempt(Supplier<T> translation, Supplier<T> placeholder) {
        if (!tolerant) {
            return translation.get();
        }
        try {
            return translation.get();
        } catch (UnsupportedCodeException e) {
            fail(e.getMessage());
            return placeholder.get();
        }
    }

    private void fail(String reason) {
        if (ids.isEmpty()) {
            throw new UnsupportedCodeException(reason);
        }
        failures.putIfAbsent(ids.peek(), reason);
    }

    private Expression parseExpression(String code) {
        ParseResult<com.github.javaparser.ast.expr.Expression> result = javaParser.parseExpression(code);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            throw new UnsupportedCodeException("syntax error in '" + oneLine(code) + "'");
        }
        return transformFromJava(result.getResult().get());
    }

    private Statement parseBlock(String code) {
        ParseResult<BlockStmt> result = javaParser.parseBlock("{" + code + "}");
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            throw new UnsupportedCodeException("syntax error in '" + oneLine(code) + "'");
        }
        return transformFromJava(result.getResult().get());
    }

    private static Expression transformFromJava(com.github.javaparser.ast.expr.Expression expr) {
        return switch (expr) {
            case BinaryExpr e -> new BinOp(
                    transformFromJava(e.getLeft()),
                    transformFromJava(e.getRight()),
                    transformFromJava(e.getOperator())
            );
            case NameExpr e -> new Var(e.getNameAsString());
            case IntegerLiteralExpr e -> new Const(e.asNumber().intValue());
            default -> throw new UnsupportedCodeException("expression '" + oneLine(expr) + "' is not supported");
        };
    }

    private static Statement transformFromJava(BlockStmt block) {
        return block.getStatements().stream()
                .map(Transformer::transformFromJava)
                .reduce(StatementConcat::new)
                .orElse(new Skip());
    }

    private static Statement transformFromJava(com.github.javaparser.ast.stmt.Statement stmt) {
        if (stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().isAssignExpr()) {
            // fix difference in classification of statement and expression
            AssignExpr assignExpr = stmt.asExpressionStmt().getExpression().asAssignExpr();
            if (assignExpr.getOperator() == AssignExpr.Operator.ASSIGN && assignExpr.getTarget().isNameExpr()) {
                return new VarAssignment(
                        assignExpr.getTarget().toString(),
                        transformFromJava(assignExpr.getValue())
                );
            }
        }

        throw new UnsupportedCodeException("statement '" + oneLine(stmt) + "' is not supported");
    }

    private static String oneLine(Object code) {
        return String.valueOf(code).strip().replaceAll("\\s+", " ");
    }

    private static BinOp.Op transformFromJava(BinaryExpr.Operator op) {
        return switch (op) {
            case OR, BINARY_OR -> BinOp.Op.OR;
            case AND, BINARY_AND -> BinOp.Op.AND;
            case XOR -> BinOp.Op.XOR;
            case EQUALS -> BinOp.Op.EQ;
            case NOT_EQUALS -> BinOp.Op.NE;
            case LESS -> BinOp.Op.LT;
            case GREATER -> BinOp.Op.GT;
            case LESS_EQUALS -> BinOp.Op.LE;
            case GREATER_EQUALS -> BinOp.Op.GE;
            case LEFT_SHIFT -> BinOp.Op.SHL;
            case SIGNED_RIGHT_SHIFT -> BinOp.Op.SHR;
            case UNSIGNED_RIGHT_SHIFT -> BinOp.Op.USHR;
            case PLUS -> BinOp.Op.ADD;
            case MINUS -> BinOp.Op.MIN;
            case MULTIPLY -> BinOp.Op.MUL;
            case DIVIDE -> BinOp.Op.DIV;
            case REMAINDER -> BinOp.Op.MOD;
        };
    }

    /** Code outside the supported subset, or a syntax error. The message is short and user-readable. */
    static final class UnsupportedCodeException extends IllegalArgumentException {
        UnsupportedCodeException(String message) {
            super(message);
        }
    }
}
