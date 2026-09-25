package edu.kit.kastel.tva.eebc.lang.transformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tu_bs.cs.isf.cbc.cbcmodel.AbstractStatement;
import de.tu_bs.cs.isf.cbc.cbcmodel.CbCFormula;
import de.tu_bs.cs.isf.cbc.cbcmodel.CompositionStatement;
import de.tu_bs.cs.isf.cbc.cbcmodel.Condition;
import de.tu_bs.cs.isf.cbc.cbcmodel.SelectionStatement;
import de.tu_bs.cs.isf.cbc.cbcmodel.SkipStatement;
import de.tu_bs.cs.isf.cbc.cbcmodel.SmallRepetitionStatement;
import de.tu_bs.cs.isf.cbc.cbcmodel.Variant;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads CbC models, either CorC XMI ({@code .cbcmodel}) or WebCorC JSON {@code program}s
 * (see {@link #readProgram(String)}). {@link #readModelString(String)} picks the format by the first
 * non-whitespace character ({@code '{'} means JSON).
 */
public class CbCModelReader {
    private static final JAXBContext CONTEXT;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    static {
        try {
            CONTEXT = JAXBContext.newInstance(CbCFormula.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("JAXB could not load context for class created automatically from XSD.", e);
        }
    }

    public static CbCFormula readModelInputStream(InputStream stream) throws JAXBException, IOException {
        return readModelString(new String(stream.readAllBytes()));
    }

    public static CbCFormula readModelFile(File file) throws JAXBException, IOException {
        return readModelString(Files.readString(file.toPath()));
    }

    /**
     * Reads a CbC model from XMI or from a WebCorC JSON {@code program}. For JSON this is
     * {@code readProgram(string).formula()}.
     *
     * @throws InvalidProgramException if a JSON program is structurally broken
     */
    public static CbCFormula readModelString(String string) throws JAXBException, IOException {
        int i = 0;
        while (i < string.length() && Character.isWhitespace(string.charAt(i))) {
            i++;
        }
        if (i < string.length() && string.charAt(i) == '{') {
            return readModelJson(string);
        }
        return readModelXmi(string);
    }

    private static CbCFormula readModelXmi(String string) throws JAXBException {
        Unmarshaller unmarshaller = CONTEXT.createUnmarshaller();
        String formulaString = extractFormulaFromXmi(string);
        Source source = new StreamSource(new StringReader(formulaString));
        return unmarshaller.unmarshal(source, CbCFormula.class).getValue();
    }

    private static String extractFormulaFromXmi(String xmi) {
        int start = xmi.indexOf("<cbcmodel:CbCFormula");
        int end = xmi.lastIndexOf("</cbcmodel:CbCFormula>") + "</cbcmodel:CbCFormula>".length();

        if (start == -1 || end == -1) {
            throw new IllegalArgumentException("Invalid XMI format: CbCFormula not found.");
        }

        String substr = xmi.substring(start, end);
        substr = "<?xml version=\"1.0\" encoding=\"ASCII\"?>\n" + substr;
        substr = substr.replaceFirst("<cbcmodel:CbCFormula", "<cbcmodel:CbCFormula xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:cbcmodel=\"http://cbc.isf.cs.tu-bs.de/cbcmodel\"");
        return substr;
    }

    /**
     * Reads a WebCorC {@code program} in the JSON syntax of the Verifier specification (section 2.1).
     *
     * @param json the {@code program} object as JSON text
     * @return the program, see {@link WebCorCProgram}
     * @throws JsonProcessingException if {@code json} is not valid JSON
     * @throws InvalidProgramException if the program is structurally broken
     */
    public static WebCorCProgram readProgram(String json) throws JsonProcessingException {
        return readProgram(JSON_MAPPER.readTree(json));
    }

    /**
     * Reads a WebCorC {@code program} in the JSON syntax of the Verifier specification (section 2.1).
     * <p>
     * Only the fields needed for EEbC are validated: every statement needs an integer {@code id} (unique in the
     * program), a known {@code type}, and its children ({@code firstStatement}/{@code secondStatement};
     * {@code guards}/{@code commands} of equal, non-zero length; {@code guard}/{@code loopStatement}), and guards
     * must be {@code {"condition": "..."}} objects. A missing {@code name} is read as {@code ""}, a missing
     * {@code programStatement} as {@code ""} (an unrefined statement), a missing {@code javaVariables} as empty.
     * A {@code variant} whose condition is blank is treated as absent. Pre-, post- and intermediate conditions
     * and the invariant are carried over but never validated. Unknown fields are ignored.
     *
     * @param program the {@code program} object
     * @return the program, see {@link WebCorCProgram}
     * @throws InvalidProgramException if the program is structurally broken
     */
    public static WebCorCProgram readProgram(JsonNode program) {
        if (program == null || !program.isObject()) {
            throw new InvalidProgramException(null, null, "program must be a JSON object");
        }
        List<String> javaVariables = new ArrayList<>();
        JsonNode variables = program.get("javaVariables");
        if (variables != null && !variables.isNull()) {
            if (!variables.isArray()) {
                throw new InvalidProgramException(null, null, "'javaVariables' must be an array of strings");
            }
            for (JsonNode variable : variables) {
                if (!variable.isTextual()) {
                    throw new InvalidProgramException(null, null, "'javaVariables' must be an array of strings");
                }
                javaVariables.add(variable.asText());
            }
        }

        JsonNode rootNode = program.get("statement");
        if (rootNode == null || !rootNode.isObject()) {
            throw new InvalidProgramException(null, null, "missing 'statement'");
        }
        JsonStatementReader reader = new JsonStatementReader();
        CbCFormula formula = new CbCFormula();
        formula.setName(optionalText(program, "name"));
        formula.setPreCondition(optionalCondition(program.get("preCondition")));
        formula.setPostCondition(optionalCondition(program.get("postCondition")));
        formula.setStatement(reader.read(rootNode, null));
        return new WebCorCProgram(formula, javaVariables, Integer.parseInt(formula.getStatement().getId()),
                reader.statements);
    }

    private static CbCFormula readModelJson(String string) throws IOException {
        return readProgram(string).formula();
    }

    /** Reads the statement tree; {@link #statements} collects the structure in pre-order. */
    private static final class JsonStatementReader {
        private final Map<Integer, ProgramStatement> statements = new LinkedHashMap<>();

        AbstractStatement read(JsonNode node, Integer parentId) {
            String name = node.hasNonNull("name") ? node.get("name").asText() : "";
            JsonNode idNode = node.get("id");
            if (idNode == null || !idNode.isIntegralNumber() || !idNode.canConvertToInt()) {
                throw new InvalidProgramException(null, name, "missing or non-integer 'id'");
            }
            int id = idNode.asInt();
            if (statements.containsKey(id)) {
                throw new InvalidProgramException(id, name, "duplicate id " + id);
            }
            statements.put(id, null); // reserve the pre-order position; replaced below
            String typeName = node.hasNonNull("type") ? node.get("type").asText() : "";
            ProgramStatement.Type type;
            try {
                type = ProgramStatement.Type.valueOf(typeName);
            } catch (IllegalArgumentException e) {
                throw new InvalidProgramException(id, name, typeName.isEmpty()
                        ? "missing 'type'" : "unknown type '" + typeName + "'");
            }

            List<Integer> children = new ArrayList<>();
            AbstractStatement refinement = switch (type) {
                case STATEMENT -> {
                    AbstractStatement s = new AbstractStatement();
                    s.setName(node.hasNonNull("programStatement") ? node.get("programStatement").asText() : "");
                    yield s;
                }
                case SKIP -> {
                    SkipStatement s = new SkipStatement();
                    s.setName("");
                    yield s;
                }
                case COMPOSITION -> {
                    CompositionStatement s = new CompositionStatement();
                    s.setName(name);
                    s.setFirstStatement(child(node, "firstStatement", id, name, children));
                    s.setSecondStatement(child(node, "secondStatement", id, name, children));
                    s.setIntermediateCondition(optionalCondition(node.get("intermediateCondition")));
                    yield s;
                }
                case SELECTION -> {
                    SelectionStatement s = new SelectionStatement();
                    s.setName(name);
                    JsonNode guards = node.get("guards");
                    JsonNode commands = node.get("commands");
                    if (guards == null || !guards.isArray()) {
                        throw new InvalidProgramException(id, name, "missing 'guards'");
                    }
                    if (commands == null || !commands.isArray()) {
                        throw new InvalidProgramException(id, name, "missing 'commands'");
                    }
                    if (guards.size() != commands.size()) {
                        throw new InvalidProgramException(id, name, "selection has " + guards.size()
                                + " guards but " + commands.size() + " commands");
                    }
                    if (guards.isEmpty()) {
                        throw new InvalidProgramException(id, name, "selection has no branches");
                    }
                    for (JsonNode guard : guards) {
                        s.getGuards().add(requiredCondition(guard, "guards", id, name));
                    }
                    for (JsonNode command : commands) {
                        if (!command.isObject()) {
                            throw new InvalidProgramException(id, name, "'commands' must contain statements");
                        }
                        s.getCommands().add(read(command, id));
                        children.add(Integer.parseInt(s.getCommands().getLast().getId()));
                    }
                    yield s;
                }
                case REPETITION -> {
                    SmallRepetitionStatement s = new SmallRepetitionStatement();
                    s.setName(name);
                    s.setGuard(requiredCondition(node.get("guard"), "guard", id, name));
                    s.setInvariant(optionalCondition(node.get("invariant")));
                    Condition variantCondition = optionalCondition(node.get("variant"));
                    if (variantCondition != null && !variantCondition.getName().isBlank()) {
                        Variant variant = new Variant();
                        variant.setName(variantCondition.getName());
                        s.setVariant(variant);
                    }
                    s.setLoopStatement(child(node, "loopStatement", id, name, children));
                    yield s;
                }
            };
            refinement.setId(Integer.toString(id));

            AbstractStatement wrapper = new AbstractStatement();
            wrapper.setName(name);
            wrapper.setId(Integer.toString(id));
            wrapper.setPreCondition(optionalCondition(node.get("preCondition")));
            wrapper.setPostCondition(optionalCondition(node.get("postCondition")));
            wrapper.setRefinement(refinement);

            statements.put(id, new ProgramStatement(id, name, type, parentId, children));
            return wrapper;
        }

        private AbstractStatement child(JsonNode node, String field, int id, String name, List<Integer> children) {
            JsonNode child = node.get(field);
            if (child == null || !child.isObject()) {
                throw new InvalidProgramException(id, name, "missing '" + field + "'");
            }
            AbstractStatement statement = read(child, id);
            children.add(Integer.parseInt(statement.getId()));
            return statement;
        }

        private static Condition requiredCondition(JsonNode node, String field, int id, String name) {
            if (node == null || !node.isObject() || !node.hasNonNull("condition")) {
                throw new InvalidProgramException(id, name, "missing condition in '" + field + "'");
            }
            return optionalCondition(node);
        }
    }

    private static Condition optionalCondition(JsonNode node) {
        if (node == null || !node.isObject() || !node.hasNonNull("condition")) {
            return null;
        }
        Condition c = new Condition();
        c.setName(node.get("condition").asText());
        return c;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}