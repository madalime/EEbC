package edu.kit.kastel.tva.eebc.verifier.analysis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one analysis produced.
 *
 * @param statements the result for every statement of the program, by statement id, in pre-order
 * @param log        the console lines sent to the user, in order
 * @param proven     the verdict for the whole program ({@code done.proven}): {@code true} iff every statement is
 *                   proven
 * @param status     the status of the whole program ({@code done.status}), e.g.
 *                   {@code total energy estimate: 42 / 50}
 */
public record AnalysisReport(Map<Integer, StatementResult> statements, List<String> log, boolean proven,
                             String status) {
    public AnalysisReport {
        statements = Collections.unmodifiableMap(new LinkedHashMap<>(statements));
        log = List.copyOf(log);
    }
}
