package edu.kit.kastel.tva.eebc.lang.transformation;

import edu.kit.kastel.tva.eebc.lang.ast.Statement;

import java.util.Map;

/**
 * Result of {@link Transformer#transformWithIds(de.tu_bs.cs.isf.cbc.cbcmodel.CbCFormula)}.
 *
 * @param root     the AST root; always the node of the root statement
 * @param nodes    for every statement with an integer id, the AST node translated from it. The nodes of different
 *                 statements are distinct objects (compare them by identity; AST nodes do not override
 *                 {@code equals}). Node kinds: composition &rarr; {@code StatementConcat}; selection &rarr; the first
 *                 {@code IfThenElse} of its chain; repetition &rarr; {@code Repeat}; skip &rarr; {@code Skip};
 *                 leaf &rarr; its parsed Java block (a single statement, or a {@code StatementConcat} chain);
 *                 unrefined leaf &rarr; {@code StatementConcat(null, null)}. A statement listed in
 *                 {@code failures} still has a node here (a placeholder or a partly placeholder node).
 * @param failures for statements that could not be translated completely, a short user-readable reason, e.g.
 *                 {@code loop has no variant} or {@code statement 'i++;' is not supported}. A failure in a guard or
 *                 variant belongs to its selection/repetition. Only the first failure of a statement is kept.
 */
public record TransformationResult(Statement root, Map<Integer, Statement> nodes, Map<Integer, String> failures) {
    public TransformationResult {
        nodes = Map.copyOf(nodes);
        failures = Map.copyOf(failures);
    }
}
