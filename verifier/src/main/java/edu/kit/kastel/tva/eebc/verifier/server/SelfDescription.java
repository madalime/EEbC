package edu.kit.kastel.tva.eebc.verifier.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.kit.kastel.tva.eebc.verifier.analysis.AnalysisSettings;
import edu.kit.kastel.tva.eebc.verifier.analysis.HardwareModels;

/** The Self-Description (Verifier specification, section 1) served at {@code GET /description}. */
final class SelfDescription {
    static final String LABEL = "Energy (EEbC)";
    static final String STATUS_PLACEHOLDER = "Energy not yet analysed";

    static final String UPPER_BOUND_DESCRIPTION = "A statement passes if its energy estimate is at most this bound. "
            + "Energy values are EEbC's plain integers (no unit), computed with the chosen hardware model.";
    static final String HARDWARE_MODEL_DESCRIPTION = "The hardware model that assigns time and energy costs "
            + "to each operation.";
    static final String INITIAL_STATE_DESCRIPTION = "Comma-separated name=value pairs with whole-number values "
            + "(32-bit), e.g. \"n=10, i=0\". Variables that are not listed start at 0; leave empty to start all "
            + "variables at 0. The program is analysed for one run from this state: branches and loop bodies that "
            + "this run does not reach are reported as not executed, so a passing result means \"within budget for "
            + "this input\", not for all inputs. Loops are analysed with their variant.";

    private SelfDescription() {
    }

    static ObjectNode build(ObjectMapper mapper, HardwareModels registry) {
        ObjectNode description = mapper.createObjectNode();
        description.put("label", LABEL);
        description.put("enabled", false);
        description.put("toggleable", true);
        description.put("statusPlaceholder", STATUS_PLACEHOLDER);
        ArrayNode settings = description.putArray("settings");

        ObjectNode upperBound = settings.addObject();
        upperBound.put("id", AnalysisSettings.UPPER_BOUND);
        upperBound.put("type", "text");
        upperBound.put("valueType", "number");
        upperBound.put("label", "Energy upper bound");
        upperBound.put("description", UPPER_BOUND_DESCRIPTION);
        upperBound.put("step", 1);
        upperBound.putObject("range").put("min", 0);
        upperBound.put("required", true);
        upperBound.put("default", AnalysisSettings.DEFAULT_UPPER_BOUND);

        ObjectNode hardwareModel = settings.addObject();
        hardwareModel.put("id", AnalysisSettings.HARDWARE_MODEL);
        hardwareModel.put("type", "select");
        hardwareModel.put("label", "Hardware model");
        hardwareModel.put("description", HARDWARE_MODEL_DESCRIPTION);
        ArrayNode options = hardwareModel.putArray("options");
        for (HardwareModels.Entry entry : registry.entries()) {
            options.addObject().put("id", entry.id()).put("label", entry.label());
        }
        hardwareModel.put("required", true);
        hardwareModel.put("default", registry.defaultId());

        ObjectNode initialState = settings.addObject();
        initialState.put("id", AnalysisSettings.INITIAL_STATE);
        initialState.put("type", "text");
        initialState.put("label", "Initial variable values");
        initialState.put("description", INITIAL_STATE_DESCRIPTION);
        initialState.put("required", false);
        initialState.put("default", AnalysisSettings.DEFAULT_INITIAL_STATE);
        return description;
    }
}
