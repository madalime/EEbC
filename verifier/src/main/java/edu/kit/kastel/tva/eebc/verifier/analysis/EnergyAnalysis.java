package edu.kit.kastel.tva.eebc.verifier.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import edu.kit.kastel.tva.eebc.lang.transformation.CbCModelReader;
import edu.kit.kastel.tva.eebc.lang.transformation.ProgramStatement;
import edu.kit.kastel.tva.eebc.lang.transformation.TransformationResult;
import edu.kit.kastel.tva.eebc.lang.transformation.Transformer;
import edu.kit.kastel.tva.eebc.lang.transformation.WebCorCProgram;
import edu.kit.kastel.tva.eebc.typesystem.ComponentState;
import edu.kit.kastel.tva.eebc.typesystem.ProgramState;
import edu.kit.kastel.tva.eebc.typesystem.State;
import edu.kit.kastel.tva.eebc.typesystem.StatementType;
import edu.kit.kastel.tva.eebc.typesystem.StatementType.TypingState;
import edu.kit.kastel.tva.eebc.verifier.analysis.ObservingTypingVisitor.Recording;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The analysis entry point of the Verifier: {@code (program, settings) → per-statement results, log lines, done}.
 * <p>
 * It defines no energy semantics of its own. It runs EEbC exactly like the EEbC demo does (translate, type with
 * {@code TypingVisitor}, evaluate {@code s}, {@code e} and {@code typingState} of the root from the start state) and
 * derives every result from what it observes during that one run (see {@link ObservingTypingVisitor}):
 * <ul>
 *     <li>A statement that was not executed passes with {@code not executed for this input}.</li>
 *     <li>An executed statement's energy is the maximum over its distinct runs; it passes iff it was analysed, its
 *     typing state is {@code COMPLETE}, its energy is &ge; 0 (negative means integer overflow) and &le; the upper
 *     bound.</li>
 *     <li>Translation failures are propagated structurally: the failed statement, its ancestors ("contains"), and
 *     the statements that run after it or inside it ("start state unknown") are not analysed.</li>
 *     <li>Any exception or error during the run fails every statement; nothing partial is reported.</li>
 * </ul>
 * Usage: {@link #prepare} validates the request (cheap, done before accepting a job), {@link #run} performs the
 * analysis on the calling thread. EEbC evaluates loops recursively, so call {@code run} on a thread with a large
 * stack.
 */
public final class EnergyAnalysis {
    private static final Logger LOG = LoggerFactory.getLogger(EnergyAnalysis.class);

    private static final String LOOP_WITHOUT_VARIANT = "loop has no variant";

    private final WebCorCProgram program;
    private final AnalysisSettings settings;
    private final List<String> warnings;

    private EnergyAnalysis(WebCorCProgram program, AnalysisSettings settings, List<String> warnings) {
        this.program = program;
        this.settings = settings;
        this.warnings = List.copyOf(warnings);
    }

    /** {@link #prepare(JsonNode, JsonNode, HardwareModels)} with the {@linkplain HardwareModels#standard() standard} registry. */
    public static EnergyAnalysis prepare(JsonNode program, JsonNode settings) {
        return prepare(program, settings, HardwareModels.standard());
    }

    /**
     * Reads the program and validates the settings of a job.
     *
     * @param program  the request's {@code program} (Verifier specification, section 2.1)
     * @param settings the request's {@code settings}; missing settings take their defaults, unknown ones are ignored
     * @param registry the hardware models to choose from
     * @throws InvalidRequestException with a user-readable message for a structurally broken program or an
     *                                 invalid setting value
     */
    public static EnergyAnalysis prepare(JsonNode program, JsonNode settings, HardwareModels registry) {
        WebCorCProgram webCorCProgram;
        try {
            webCorCProgram = CbCModelReader.readProgram(program);
        } catch (IllegalArgumentException e) { // InvalidProgramException: message already names the statement
            throw new InvalidRequestException(e.getMessage(), e);
        }
        AnalysisSettings analysisSettings = AnalysisSettings.parse(settings, registry);

        List<String> warnings = new ArrayList<>();
        Set<String> variables = new HashSet<>(webCorCProgram.variableNames());
        for (String name : analysisSettings.initialState().keySet()) {
            if (!variables.contains(name)) {
                warnings.add("Initial state: '" + name + "' is not a program variable");
            }
        }
        return new EnergyAnalysis(webCorCProgram, analysisSettings, warnings);
    }

    /** @return the validated settings */
    public AnalysisSettings settings() {
        return settings;
    }

    /** @return the ids of all statements of the program, in pre-order */
    public Collection<Integer> statementIds() {
        return program.statements().keySet();
    }

    /** Runs the analysis on the calling thread and collects the console lines only in the report. */
    public AnalysisReport run() {
        return run(line -> {
        });
    }

    /**
     * Runs the analysis on the calling thread. Never throws: a failure of the run is part of the report.
     *
     * @param console receives every console line as soon as it is known (they are also in the report)
     */
    public AnalysisReport run(Consumer<String> console) {
        List<String> log = new ArrayList<>();
        Consumer<String> out = line -> {
            log.add(line);
            console.accept(line);
        };
        out.accept("Hardware model: " + settings.hardwareModel().id()
                + ", upper bound: " + settings.upperBound()
                + ", initial state: " + settings.describeInitialState());
        warnings.forEach(out);

        Map<Integer, StatementResult> results;
        Integer rootEnergy;
        try {
            TransformationResult translation = Transformer.transformWithIds(program.formula());
            for (int id : program.statements().keySet()) {
                String reason = translation.failures().get(id);
                if (reason != null) {
                    out.accept(label(id) + ": " + describeFailure(reason));
                }
            }

            ObservingTypingVisitor visitor = new ObservingTypingVisitor(
                    settings.hardwareModel().factory().get(), translation.nodes());
            StatementType root = translation.root().accept(visitor);
            State start = startState();
            // exactly the evaluation of the EEbC demo
            root.s(start);
            root.e(start);
            root.typingState(start);
            visitor.recordings().values().forEach(Recording::completeTypingStates);

            Derivation derivation = new Derivation(translation.failures(), visitor.recordings());
            results = derivation.results();
            rootEnergy = derivation.rootEnergy();
        } catch (Throwable t) { // including StackOverflowError: nothing partial is reported
            String reason = describe(t);
            LOG.warn("Analysis failed: {}", reason, t);
            out.accept("Analysis failed: " + reason);
            results = new LinkedHashMap<>();
            for (int id : program.statements().keySet()) {
                results.put(id, new StatementResult(false, "not analysed: analysis failed (" + reason + ")"));
            }
            rootEnergy = null;
        }

        int proven = 0;
        int failed = 0;
        int notExecuted = 0;
        for (StatementResult result : results.values()) {
            if (!result.proven()) {
                failed++;
            } else if (Derivation.NOT_EXECUTED.equals(result.status())) {
                notExecuted++;
            } else {
                proven++;
            }
        }
        out.accept(proven + " proven, " + failed + " failed, " + notExecuted + " not executed");

        boolean allProven = failed == 0;
        String status = rootEnergy == null ? "total energy estimate unavailable"
                : "total energy estimate: " + rootEnergy + " / " + settings.upperBound();
        return new AnalysisReport(results, log, allProven, status);
    }

    /** The start state: the initial values on top of an empty program state, with an empty component state. */
    private State startState() {
        ProgramState programState = new ProgramState();
        settings.initialState().forEach(programState::setVariable);
        return new State(programState, ComponentState.empty());
    }

    private String label(int id) {
        String name = program.statement(id).name();
        return name == null || name.isBlank() ? "Statement id " + id : "Statement '" + name + "' (id " + id + ")";
    }

    private String nameOf(int id) {
        String name = program.statement(id).name();
        return name == null || name.isBlank() ? "id " + id : name;
    }

    private static String describeFailure(String reason) {
        return LOOP_WITHOUT_VARIANT.equals(reason) ? reason : "unsupported code: " + reason;
    }

    /** A short, user-readable reason for a runtime failure (the stack trace goes to the server log only). */
    private static String describe(Throwable t) {
        if (t instanceof StackOverflowError) {
            return "stack overflow";
        }
        if (t instanceof OutOfMemoryError) {
            return "out of memory";
        }
        if (t instanceof ArithmeticException && "/ by zero".equals(t.getMessage())) {
            return "division by zero";
        }
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    /** Derives the per-statement results from the translation failures and the observed run. */
    private final class Derivation {
        static final String NOT_EXECUTED = "not executed for this input";

        private final Map<Integer, Recording> recordings;
        private final Map<Integer, String> structural = new HashMap<>();
        private final Map<Integer, StatementResult> results = new LinkedHashMap<>();
        private Integer rootEnergy;

        Derivation(Map<Integer, String> failures, Map<Integer, Recording> recordings) {
            this.recordings = recordings;
            propagateFailures(failures);
            for (int id : program.statements().keySet()) {
                results.put(id, derive(id));
            }
        }

        Map<Integer, StatementResult> results() {
            return results;
        }

        /** @return the root's energy if it has a number (analysed, executed, no overflow), else {@code null} */
        Integer rootEnergy() {
            return rootEnergy;
        }

        /**
         * Structural failure propagation, in order of precedence: the failed statement itself; its ancestors
         * ("contains"); the statements that run after it, and those inside it, whose start state depends on the
         * failed part ("start state unknown"). When several failures apply, the first in program order is named.
         */
        private void propagateFailures(Map<Integer, String> failures) {
            List<Integer> failed = program.statements().keySet().stream().filter(failures::containsKey).toList();
            for (int id : failed) {
                structural.put(id, "not analysed: " + describeFailure(failures.get(id)));
            }
            for (int id : failed) {
                for (int ancestor : program.ancestors(id)) {
                    structural.putIfAbsent(ancestor,
                            "not analysed: contains statement '" + nameOf(id) + "' that could not be analysed");
                }
            }
            for (int id : failed) {
                List<Integer> affected = new ArrayList<>(program.runsAfter(id));
                affected.addAll(program.descendants(id));
                for (int after : affected) {
                    structural.putIfAbsent(after, "not analysed: start state unknown (after '" + nameOf(id) + "')");
                }
            }
        }

        private StatementResult derive(int id) {
            String failure = structural.get(id);
            if (failure != null) {
                return new StatementResult(false, failure);
            }
            if (!executed(id)) {
                return new StatementResult(true, NOT_EXECUTED);
            }

            Collection<Integer> energies = recordings.get(id).energies().values();
            boolean severalRuns = energies.size() > 1;
            int energy = energies.stream().mapToInt(Integer::intValue).max().orElseThrow();
            boolean overflow = energies.stream().anyMatch(e -> e < 0);
            boolean incomplete = incomplete(id);
            if (id == program.rootId() && !overflow) {
                rootEnergy = energy;
            }

            String estimate = (severalRuns ? "max " : "") + energy + " / " + settings.upperBound();
            if (incomplete && program.statement(id).type() == ProgramStatement.Type.STATEMENT) {
                return new StatementResult(false, "energy estimate incomplete: statement not yet refined");
            }
            if (overflow) {
                return new StatementResult(false, "energy estimate overflowed");
            }
            if (incomplete) {
                return new StatementResult(false,
                        "energy estimate incomplete: " + estimate + " (contains unrefined statements)");
            }
            if (energy <= settings.upperBound()) {
                return new StatementResult(true, "energy estimate: " + estimate);
            }
            return new StatementResult(false, "energy estimate: " + estimate + " (exceeds bound)");
        }

        private boolean executed(int id) {
            Recording recording = recordings.get(id);
            return recording != null && !recording.energies().isEmpty();
        }

        /**
         * A statement is incomplete if EEbC typed one of its runs as not {@code COMPLETE}, or if an executed
         * statement inside it was. The second part is needed because EEbC's {@code RepeatType.typingState} only
         * looks at the variant's typing state, so an unrefined statement inside a loop body does not reach the loop
         * (and its ancestors) through EEbC's own {@code combine}. The observed typing states are used as they are;
         * EEbC is not changed.
         */
        private boolean incomplete(int id) {
            if (ownRunIncomplete(id)) {
                return true;
            }
            for (int descendant : program.descendants(id)) {
                if (executed(descendant) && ownRunIncomplete(descendant)) {
                    return true;
                }
            }
            return false;
        }

        private boolean ownRunIncomplete(int id) {
            Recording recording = recordings.get(id);
            if (recording == null) {
                return false;
            }
            for (State start : recording.energies().keySet()) {
                if (recording.typingState(start) != TypingState.COMPLETE) {
                    return true;
                }
            }
            return false;
        }
    }
}
