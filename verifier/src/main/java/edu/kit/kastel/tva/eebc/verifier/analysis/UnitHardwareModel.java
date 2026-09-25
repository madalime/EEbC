package edu.kit.kastel.tva.eebc.verifier.analysis;

import edu.kit.kastel.tva.eebc.lang.ast.BinOp;
import edu.kit.kastel.tva.eebc.typesystem.ComponentState;
import edu.kit.kastel.tva.eebc.typesystem.HardwareModel;

/**
 * The unit-cost hardware model: every operation takes time 1, and energy equals time. This is the configuration of
 * the EEbC demo (its {@code TestHardwareModel} with every time set to 1), repeated here because test classes are not
 * part of the EEbC artifact.
 */
public final class UnitHardwareModel implements HardwareModel {
    @Override
    public int timeDependentEnergyConsumption(ComponentState state, int time) {
        return time;
    }

    @Override
    public int timeConst() {
        return 1;
    }

    @Override
    public int timeBinOp(BinOp.Op op) {
        return 1;
    }

    @Override
    public int timeIf() {
        return 1;
    }

    @Override
    public int timeVarAssignment() {
        return 1;
    }

    @Override
    public int timeVar() {
        return 1;
    }

    @Override
    public int timeCallCompFun(String name) {
        return 1;
    }

    @Override
    public boolean isDeterministic(String funName) {
        return false;
    }
}
