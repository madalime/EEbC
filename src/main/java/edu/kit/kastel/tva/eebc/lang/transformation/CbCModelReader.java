package edu.kit.kastel.tva.eebc.lang.transformation;

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

    private static CbCFormula readModelJson(String string) throws IOException {
        JsonNode root = JSON_MAPPER.readTree(string);
        CbCFormula formula = new CbCFormula();
        formula.setName(textOrNull(root, "name"));
        formula.setPreCondition(readCondition(root.get("preCondition")));
        formula.setPostCondition(readCondition(root.get("postCondition")));
        if (root.hasNonNull("isProven")) {
            formula.setProven(Boolean.toString(root.get("isProven").asBoolean()));
        }
        formula.setStatement(readStatement(root.get("statement")));
        return formula;
    }

    private static AbstractStatement readStatement(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String type = textOrNull(node, "type");
        AbstractStatement refinement = switch (type == null ? "" : type) {
            case "COMPOSITION" -> {
                CompositionStatement s = new CompositionStatement();
                populateCommon(s, node);
                s.setFirstStatement(readStatement(node.get("firstStatement")));
                s.setSecondStatement(readStatement(node.get("secondStatement")));
                s.setIntermediateCondition(readCondition(node.get("intermediateCondition")));
                yield s;
            }
            case "SELECTION" -> {
                SelectionStatement s = new SelectionStatement();
                populateCommon(s, node);
                JsonNode guards = node.get("guards");
                if (guards != null && guards.isArray()) {
                    for (JsonNode g : guards) {
                        s.getGuards().add(readCondition(g));
                    }
                }
                JsonNode commands = node.get("commands");
                if (commands != null && commands.isArray()) {
                    for (JsonNode c : commands) {
                        s.getCommands().add(readStatement(c));
                    }
                }
                if (node.hasNonNull("isPreProven")) {
                    s.setPreProve(Boolean.toString(node.get("isPreProven").asBoolean()));
                }
                yield s;
            }
            case "REPETITION" -> {
                SmallRepetitionStatement s = new SmallRepetitionStatement();
                populateCommon(s, node);
                s.setLoopStatement(readStatement(node.get("loopStatement")));
                s.setInvariant(readCondition(node.get("invariant")));
                s.setGuard(readCondition(node.get("guard")));
                JsonNode variantNode = node.get("variant");
                if (variantNode != null && !variantNode.isNull()) {
                    Variant variant = new Variant();
                    variant.setName(textOrNull(variantNode, "condition"));
                    s.setVariant(variant);
                }
                if (node.hasNonNull("isVariantProven")) {
                    s.setVariantProven(Boolean.toString(node.get("isVariantProven").asBoolean()));
                }
                if (node.hasNonNull("isPreProven")) {
                    s.setPreProven(Boolean.toString(node.get("isPreProven").asBoolean()));
                }
                if (node.hasNonNull("isPostProven")) {
                    s.setPostProven(Boolean.toString(node.get("isPostProven").asBoolean()));
                }
                yield s;
            }
            case "SKIP" -> {
                SkipStatement s = new SkipStatement();
                populateCommon(s, node);
                // TODO: Check if override is needed
                s.setName("");
                yield s;
            }
            default -> {
                AbstractStatement s = new AbstractStatement();
                populateCommon(s, node);
                s.setName(textOrNull(node, "programStatement"));
                yield s;
            }
        };

        AbstractStatement wrapper = new AbstractStatement();
        wrapper.setName(textOrNull(node, "name"));
        wrapper.setId(textOrNull(node, "id"));
        if (node.hasNonNull("isProven")) {
            wrapper.setProven(Boolean.toString(node.get("isProven").asBoolean()));
        }
        wrapper.setPreCondition(readCondition(node.get("preCondition")));
        wrapper.setPostCondition(readCondition(node.get("postCondition")));
        wrapper.setRefinement(refinement);
        return wrapper;
    }

    private static void populateCommon(AbstractStatement s, JsonNode node) {
        s.setName(textOrNull(node, "name"));
        s.setId(textOrNull(node, "id"));
        if (node.hasNonNull("isProven")) {
            s.setProven(Boolean.toString(node.get("isProven").asBoolean()));
        }
        s.setPreCondition(readCondition(node.get("preCondition")));
        s.setPostCondition(readCondition(node.get("postCondition")));
    }

    private static Condition readCondition(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        Condition c = new Condition();
        c.setName(textOrNull(node, "condition"));
        return c;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}