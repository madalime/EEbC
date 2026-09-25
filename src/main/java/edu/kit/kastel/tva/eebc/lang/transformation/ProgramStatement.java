package edu.kit.kastel.tva.eebc.lang.transformation;

import java.util.List;

/**
 * One statement of a WebCorC {@code program}, as read by {@link CbCModelReader#readProgram(String)}.
 * It describes only the tree structure; the code itself lives in {@link WebCorCProgram#formula()}.
 *
 * @param id       the WebCorC statement id
 * @param name     the name shown to users (empty if the JSON has none)
 * @param type     the WebCorC statement type
 * @param parentId the id of the enclosing statement, or {@code null} for the root statement
 * @param children the ids of the direct child statements, in program order:
 *                 {@code [firstStatement, secondStatement]} for a composition,
 *                 {@code commands} in guard order for a selection, {@code [loopStatement]} for a repetition,
 *                 and empty for {@code STATEMENT}/{@code SKIP}
 */
public record ProgramStatement(int id, String name, Type type, Integer parentId, List<Integer> children) {
    public ProgramStatement {
        children = List.copyOf(children);
    }

    /** The WebCorC statement types. */
    public enum Type { STATEMENT, SKIP, COMPOSITION, SELECTION, REPETITION }
}
