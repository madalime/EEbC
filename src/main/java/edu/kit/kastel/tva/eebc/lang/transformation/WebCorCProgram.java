package edu.kit.kastel.tva.eebc.lang.transformation;

import de.tu_bs.cs.isf.cbc.cbcmodel.CbCFormula;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * A WebCorC {@code program} (Verifier specification, section 2.1) as read by
 * {@link CbCModelReader#readProgram(String)}: the {@link CbCFormula} for {@link Transformer}, the declared Java
 * variables, and the statement tree indexed by WebCorC statement id.
 * <p>
 * Every {@code cbcmodel} statement object created for a WebCorC statement carries that statement's id as a
 * decimal string in {@link de.tu_bs.cs.isf.cbc.cbcmodel.AbstractStatement#getId()}. As in CorC XMI models, each
 * WebCorC statement becomes a plain {@code AbstractStatement} (holding the user-visible name) whose refinement is
 * the typed statement ({@code CompositionStatement}, {@code SelectionStatement}, {@code SmallRepetitionStatement},
 * {@code SkipStatement}, or an {@code AbstractStatement} whose name is the Java code); both carry the same id.
 */
public final class WebCorCProgram {
    private final CbCFormula formula;
    private final List<String> javaVariables;
    private final int rootId;
    private final Map<Integer, ProgramStatement> statements;

    WebCorCProgram(CbCFormula formula, List<String> javaVariables, int rootId,
                   Map<Integer, ProgramStatement> statements) {
        this.formula = formula;
        this.javaVariables = List.copyOf(javaVariables);
        this.rootId = rootId;
        this.statements = Collections.unmodifiableMap(new LinkedHashMap<>(statements));
    }

    /** @return the program as a CbC formula, ready for {@link Transformer#transformWithIds(CbCFormula)} */
    public CbCFormula formula() {
        return formula;
    }

    /** @return the program's variable declarations as written, e.g. {@code "int i"}, {@code "int[] a"} */
    public List<String> javaVariables() {
        return javaVariables;
    }

    /**
     * @return the declared variable names: the last word of each declaration without {@code []},
     * e.g. {@code "int[] a"} gives {@code "a"}
     */
    public List<String> variableNames() {
        List<String> names = new ArrayList<>();
        for (String declaration : javaVariables) {
            String[] words = declaration.trim().split("\\s+");
            String name = words[words.length - 1].replace("[]", "");
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** @return the id of the root statement */
    public int rootId() {
        return rootId;
    }

    /** @return all statements by id, in pre-order (a statement before its children, children in program order) */
    public Map<Integer, ProgramStatement> statements() {
        return statements;
    }

    /**
     * @return the statement with the given id
     * @throws NoSuchElementException if there is no such statement
     */
    public ProgramStatement statement(int id) {
        ProgramStatement statement = statements.get(id);
        if (statement == null) {
            throw new NoSuchElementException("No statement with id " + id);
        }
        return statement;
    }

    /** @return the ids of all enclosing statements, innermost (the parent) first, the root last */
    public List<Integer> ancestors(int id) {
        List<Integer> ancestors = new ArrayList<>();
        Integer parent = statement(id).parentId();
        while (parent != null) {
            ancestors.add(parent);
            parent = statement(parent).parentId();
        }
        return ancestors;
    }

    /** @return the ids of all statements strictly inside the given one, in pre-order */
    public List<Integer> descendants(int id) {
        List<Integer> descendants = new ArrayList<>();
        collectSubtree(id, descendants);
        descendants.removeFirst();
        return descendants;
    }

    /**
     * The statements that can start after the given statement has finished, in execution order of the program
     * (independent of any input): for every enclosing composition in which the given statement lies in
     * {@code firstStatement}, everything in {@code secondStatement}; and for every enclosing repetition, everything
     * in its loop body (which runs again in the next iteration) except the given statement, its ancestors and its
     * descendants. Statements in other branches of a selection are not included.
     *
     * @return the ids of those statements, in pre-order
     */
    public List<Integer> runsAfter(int id) {
        Set<Integer> excluded = new HashSet<>(ancestors(id));
        excluded.add(id);
        excluded.addAll(descendants(id));

        Set<Integer> after = new HashSet<>();
        int child = id;
        for (int ancestorId : ancestors(id)) {
            ProgramStatement ancestor = statement(ancestorId);
            if (ancestor.type() == ProgramStatement.Type.COMPOSITION && ancestor.children().getFirst() == child) {
                after.addAll(subtree(ancestor.children().get(1)));
            } else if (ancestor.type() == ProgramStatement.Type.REPETITION) {
                for (int inBody : subtree(ancestor.children().getFirst())) {
                    if (!excluded.contains(inBody)) {
                        after.add(inBody);
                    }
                }
            }
            child = ancestorId;
        }
        return statements.keySet().stream().filter(after::contains).toList();
    }

    private List<Integer> subtree(int id) {
        List<Integer> subtree = new ArrayList<>();
        collectSubtree(id, subtree);
        return subtree;
    }

    private void collectSubtree(int id, List<Integer> out) {
        out.add(id);
        for (int child : statement(id).children()) {
            collectSubtree(child, out);
        }
    }
}
