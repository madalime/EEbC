package edu.kit.kastel.tva.eebc.verifier.analysis;

import edu.kit.kastel.tva.eebc.lang.ast.IfThenElse;
import edu.kit.kastel.tva.eebc.lang.ast.Repeat;
import edu.kit.kastel.tva.eebc.lang.ast.Skip;
import edu.kit.kastel.tva.eebc.lang.ast.Statement;
import edu.kit.kastel.tva.eebc.lang.ast.StatementConcat;
import edu.kit.kastel.tva.eebc.lang.ast.VarAssignment;
import edu.kit.kastel.tva.eebc.typesystem.HardwareModel;
import edu.kit.kastel.tva.eebc.typesystem.State;
import edu.kit.kastel.tva.eebc.typesystem.StatementType;
import edu.kit.kastel.tva.eebc.typesystem.StatementType.TypingState;
import edu.kit.kastel.tva.eebc.typesystem.TypingVisitor;
import edu.kit.kastel.tva.eebc.typesystem.asttypes.IfThenElseType;
import edu.kit.kastel.tva.eebc.typesystem.asttypes.RepeatType;
import edu.kit.kastel.tva.eebc.typesystem.asttypes.SkipType;
import edu.kit.kastel.tva.eebc.typesystem.asttypes.StatementConcatType;
import edu.kit.kastel.tva.eebc.typesystem.asttypes.VarAssignmentType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EEbC's {@link TypingVisitor}, observed: every {@link StatementType} built for the AST node of a WebCorC statement
 * is replaced by a recording subclass of the very type EEbC would have built. The subclass calls the EEbC
 * implementation for everything and only records, per statement id, each distinct start state it was evaluated
 * with, together with the energy {@code e} and the typing state EEbC computed for it. Nothing is changed or
 * recomputed differently; plain {@code s} calls are passed through untouched.
 * <p>
 * Subclassing (rather than wrapping in a separate decorator object) is needed because {@link TypingVisitor}'s
 * {@code visit*} methods declare the concrete EEbC types as return types.
 * <p>
 * A statement counts as executed from a state iff EEbC evaluated its energy {@code e} from that state: EEbC only
 * evaluates {@code e} of a selection branch that is taken and of a loop body once per iteration that runs.
 */
final class ObservingTypingVisitor extends TypingVisitor {
    private final HardwareModel hardwareModel;
    private final Map<Statement, Integer> ids = new IdentityHashMap<>();
    private final Map<Integer, Recording> recordings = new LinkedHashMap<>();

    /**
     * @param hardwareModel the hardware model, passed on to EEbC unchanged
     * @param nodes         the AST node of every WebCorC statement id (matched by identity)
     */
    ObservingTypingVisitor(HardwareModel hardwareModel, Map<Integer, Statement> nodes) {
        super(hardwareModel);
        this.hardwareModel = hardwareModel;
        nodes.forEach((id, node) -> ids.put(node, id));
    }

    /** @return the recording of every WebCorC statement whose node has been typed, by statement id */
    Map<Integer, Recording> recordings() {
        return Collections.unmodifiableMap(recordings);
    }

    private Recording recordingFor(Statement node) {
        Integer id = ids.get(node);
        return id == null ? null : recordings.computeIfAbsent(id, Recording::new);
    }

    @Override
    public IfThenElseType visitIfThenElse(IfThenElse ifThenElse) {
        Recording recording = recordingFor(ifThenElse);
        return recording == null ? super.visitIfThenElse(ifThenElse)
                : new RecordingIfThenElseType(ifThenElse, this, hardwareModel, recording);
    }

    @Override
    public RepeatType visitRepeat(Repeat repeat) {
        Recording recording = recordingFor(repeat);
        return recording == null ? super.visitRepeat(repeat) : new RecordingRepeatType(repeat, this, recording);
    }

    @Override
    public SkipType visitSkip(Skip skip) {
        Recording recording = recordingFor(skip);
        return recording == null ? super.visitSkip(skip) : new RecordingSkipType(recording);
    }

    @Override
    public StatementConcatType visitStatementConcat(StatementConcat statementConcat) {
        Recording recording = recordingFor(statementConcat);
        return recording == null ? super.visitStatementConcat(statementConcat)
                : new RecordingStatementConcatType(statementConcat, this, recording);
    }

    @Override
    public VarAssignmentType visitVarAssignment(VarAssignment varAssign) {
        Recording recording = recordingFor(varAssign);
        return recording == null ? super.visitVarAssignment(varAssign)
                : new RecordingVarAssignmentType(varAssign, this, hardwareModel, recording);
    }

    /**
     * What EEbC computed for one WebCorC statement during the run: per distinct start state (in order of first
     * evaluation), the energy and the typing state. Evaluations from equal states are merged.
     */
    static final class Recording {
        private final int id;
        private StatementType type;
        private final Map<State, Integer> energies = new LinkedHashMap<>();
        private final Map<State, TypingState> typingStates = new HashMap<>();

        private Recording(int id) {
            this.id = id;
        }

        int id() {
            return id;
        }

        /** @return the energy per start state from which the statement was executed, in order of execution */
        Map<State, Integer> energies() {
            return Collections.unmodifiableMap(energies);
        }

        /** @return EEbC's typing state for the given start state, or {@code null} if EEbC never computed it */
        TypingState typingState(State start) {
            return typingStates.get(start);
        }

        /**
         * EEbC does not ask every executed statement for its typing state: a loop's typing state only looks at its
         * variant, and a selection's only at the branch it would take. For every executed start state whose typing
         * state EEbC has not computed, ask EEbC's own type of this statement for it (from that same state).
         */
        void completeTypingStates() {
            List<State> missing = new ArrayList<>();
            for (State start : energies.keySet()) {
                if (!typingStates.containsKey(start)) {
                    missing.add(start);
                }
            }
            for (State start : missing) {
                if (!typingStates.containsKey(start)) {
                    type.typingState(start);
                }
            }
        }

        private void attach(StatementType type) {
            this.type = type;
        }

        private int energy(State before, int energy) {
            if (!energies.containsKey(before)) {
                energies.put(before.copy(), energy);
            }
            return energy;
        }

        private TypingState typingState(State before, TypingState typingState) {
            if (!typingStates.containsKey(before)) {
                typingStates.put(before.copy(), typingState);
            }
            return typingState;
        }
    }

    private static final class RecordingIfThenElseType extends IfThenElseType {
        private final Recording recording;

        RecordingIfThenElseType(IfThenElse node, TypingVisitor visitor, HardwareModel hardwareModel,
                                Recording recording) {
            super(node, visitor, hardwareModel);
            this.recording = recording;
            recording.attach(this);
        }

        @Override
        public int e(State before) {
            return recording.energy(before, super.e(before));
        }

        @Override
        public TypingState typingState(State before) {
            return recording.typingState(before, super.typingState(before));
        }
    }

    private static final class RecordingRepeatType extends RepeatType {
        private final Recording recording;

        RecordingRepeatType(Repeat node, TypingVisitor visitor, Recording recording) {
            super(node, visitor);
            this.recording = recording;
            recording.attach(this);
        }

        @Override
        public int e(State before) {
            return recording.energy(before, super.e(before));
        }

        @Override
        public TypingState typingState(State before) {
            return recording.typingState(before, super.typingState(before));
        }
    }

    private static final class RecordingSkipType extends SkipType {
        private final Recording recording;

        RecordingSkipType(Recording recording) {
            this.recording = recording;
            recording.attach(this);
        }

        @Override
        public int e(State before) {
            return recording.energy(before, super.e(before));
        }

        @Override
        public TypingState typingState(State before) {
            return recording.typingState(before, super.typingState(before));
        }
    }

    private static final class RecordingStatementConcatType extends StatementConcatType {
        private final Recording recording;

        RecordingStatementConcatType(StatementConcat node, TypingVisitor visitor, Recording recording) {
            super(node, visitor);
            this.recording = recording;
            recording.attach(this);
        }

        @Override
        public int e(State before) {
            return recording.energy(before, super.e(before));
        }

        @Override
        public TypingState typingState(State before) {
            return recording.typingState(before, super.typingState(before));
        }
    }

    private static final class RecordingVarAssignmentType extends VarAssignmentType {
        private final Recording recording;

        RecordingVarAssignmentType(VarAssignment node, TypingVisitor visitor, HardwareModel hardwareModel,
                                   Recording recording) {
            super(node, visitor, hardwareModel);
            this.recording = recording;
            recording.attach(this);
        }

        @Override
        public int e(State before) {
            return recording.energy(before, super.e(before));
        }

        @Override
        public TypingState typingState(State before) {
            return recording.typingState(before, super.typingState(before));
        }
    }
}
