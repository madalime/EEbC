package edu.kit.kastel.tva.eebc.verifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.kit.kastel.tva.eebc.lang.ast.BinOp;
import edu.kit.kastel.tva.eebc.typesystem.ComponentState;
import edu.kit.kastel.tva.eebc.typesystem.HardwareModel;
import edu.kit.kastel.tva.eebc.verifier.analysis.HardwareModels;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Test fixtures: JSON programs in the WebCorC program syntax, and test hardware models. */
public final class Fixtures {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Fixtures() {
    }

    /** @return the fixture program {@code programs/<name>.json} */
    public static JsonNode program(String name) {
        String path = "/edu/kit/kastel/tva/eebc/verifier/programs/" + name + ".json";
        try (InputStream stream = Fixtures.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalArgumentException("missing fixture " + path);
            }
            return MAPPER.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return a settings object; {@code null} values are left out */
    public static ObjectNode settings(String upperBound, String hardwareModel, String initialState) {
        ObjectNode settings = MAPPER.createObjectNode();
        if (upperBound != null) {
            settings.put("upperBound", upperBound);
        }
        if (hardwareModel != null) {
            settings.put("hardwareModel", hardwareModel);
        }
        if (initialState != null) {
            settings.put("initialState", initialState);
        }
        return settings;
    }

    /** A hardware model in which every operation costs {@code cost} (energy = time), as the demo's with 1. */
    public static HardwareModel costEverything(int cost) {
        return new HardwareModel() {
            @Override
            public int timeDependentEnergyConsumption(ComponentState state, int time) {
                return time;
            }

            @Override
            public int timeConst() {
                return cost;
            }

            @Override
            public int timeBinOp(BinOp.Op op) {
                return cost;
            }

            @Override
            public int timeIf() {
                return cost;
            }

            @Override
            public int timeVarAssignment() {
                return cost;
            }

            @Override
            public int timeVar() {
                return cost;
            }

            @Override
            public int timeCallCompFun(String name) {
                return cost;
            }

            @Override
            public boolean isDeterministic(String funName) {
                return false;
            }
        };
    }

    /** The standard registry plus {@code huge}, where every operation costs 10^9 (for overflow tests). */
    public static HardwareModels registryWithHugeModel() {
        return HardwareModels.standard().with(
                new HardwareModels.Entry("huge", "Every operation costs 10^9", () -> costEverything(1_000_000_000)));
    }
}
