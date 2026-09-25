package edu.kit.kastel.tva.eebc.verifier.analysis;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The validated settings of one job.
 *
 * @param upperBound    the energy upper bound, a whole number &ge; 0; a statement passes if its energy &le; this
 * @param hardwareModel the chosen entry of the hardware model registry
 * @param initialState  the initial variable values, in the order given; unset variables are 0
 */
public record AnalysisSettings(long upperBound, HardwareModels.Entry hardwareModel, Map<String, Integer> initialState) {
    /** Setting id of the energy upper bound. */
    public static final String UPPER_BOUND = "upperBound";
    /** Setting id of the hardware model. */
    public static final String HARDWARE_MODEL = "hardwareModel";
    /** Setting id of the initial variable values. */
    public static final String INITIAL_STATE = "initialState";

    /** Default of {@link #UPPER_BOUND}. */
    public static final String DEFAULT_UPPER_BOUND = "0";
    /** Default of {@link #INITIAL_STATE}: all variables start at 0. */
    public static final String DEFAULT_INITIAL_STATE = "";

    private static final Pattern WHOLE_NUMBER = Pattern.compile("\\+?(\\d+)(\\.0*)?");
    private static final Pattern PAIR = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([+-]?\\d+)");

    public AnalysisSettings {
        initialState = Collections.unmodifiableMap(new LinkedHashMap<>(initialState));
    }

    /**
     * Validates the {@code settings} object of a job. A missing setting (or a JSON {@code null}) takes its default;
     * unknown settings are ignored.
     *
     * @throws InvalidRequestException with a user-readable message for an invalid value
     */
    public static AnalysisSettings parse(JsonNode settings, HardwareModels registry) {
        if (settings == null || !settings.isObject()) {
            throw new InvalidRequestException("'settings' must be a JSON object");
        }
        String bound = value(settings, UPPER_BOUND, DEFAULT_UPPER_BOUND, true);
        String model = value(settings, HARDWARE_MODEL, registry.defaultId(), false);
        String state = value(settings, INITIAL_STATE, DEFAULT_INITIAL_STATE, false);

        HardwareModels.Entry entry = registry.find(model).orElseThrow(() -> new InvalidRequestException(
                "Unknown hardware model '" + model + "' (available: "
                        + registry.entries().stream().map(HardwareModels.Entry::id).collect(Collectors.joining(", "))
                        + ")"));
        return new AnalysisSettings(parseUpperBound(bound), entry, parseInitialState(state));
    }

    private static String value(JsonNode settings, String id, String defaultValue, boolean numberAllowed) {
        JsonNode node = settings.get(id);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (node.isTextual() || (numberAllowed && node.isNumber())) {
            return node.asText();
        }
        throw new InvalidRequestException("Setting '" + id + "' must be a string");
    }

    /**
     * Parses the energy upper bound: a whole number &ge; 0, written as a decimal number (e.g. {@code "50"} or
     * {@code "50.0"}).
     *
     * @throws InvalidRequestException if it is not a whole number &ge; 0 or does not fit a {@code long}
     */
    public static long parseUpperBound(String text) {
        Matcher matcher = WHOLE_NUMBER.matcher(text.strip());
        if (!matcher.matches()) {
            throw new InvalidRequestException("Energy upper bound must be a whole number ≥ 0, got '" + text + "'");
        }
        BigInteger value = new BigInteger(matcher.group(1));
        if (value.bitLength() >= Long.SIZE) {
            throw new InvalidRequestException("Energy upper bound is too large, got '" + text + "'");
        }
        return value.longValue();
    }

    /**
     * Parses the {@code initialState} grammar: comma-separated {@code name=value} pairs with whitespace allowed around
     * every token and an optional trailing comma; {@code name} is {@code [A-Za-z_][A-Za-z0-9_]*} and {@code value}
     * a 32-bit signed integer. Empty or blank means all variables are 0.
     *
     * @return the pairs in the order given
     * @throws InvalidRequestException for malformed input (quoting the bad part) or a name given twice
     */
    public static Map<String, Integer> parseInitialState(String text) {
        Map<String, Integer> values = new LinkedHashMap<>();
        String rest = text.strip();
        if (rest.isEmpty()) {
            return values;
        }
        if (rest.endsWith(",")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        for (String part : rest.split(",", -1)) {
            String pair = part.strip();
            if (pair.isEmpty()) {
                throw new InvalidRequestException("Initial state: empty entry between commas");
            }
            Matcher matcher = PAIR.matcher(pair);
            if (!matcher.matches()) {
                throw new InvalidRequestException("Initial state: expected name=integer at '" + pair + "'");
            }
            String name = matcher.group(1);
            int value;
            try {
                value = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException e) {
                throw new InvalidRequestException(
                        "Initial state: value of '" + name + "' is not a 32-bit integer at '" + pair + "'");
            }
            if (values.putIfAbsent(name, value) != null) {
                throw new InvalidRequestException("Initial state: '" + name + "' is given twice");
            }
        }
        return values;
    }

    /** @return the initial state as shown in the console, e.g. {@code n=10, i=0}, or {@code all 0} */
    public String describeInitialState() {
        if (initialState.isEmpty()) {
            return "all 0";
        }
        return initialState.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }
}
