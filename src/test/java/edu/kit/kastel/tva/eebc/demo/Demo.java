package edu.kit.kastel.tva.eebc.demo;

import de.tu_bs.cs.isf.cbc.cbcmodel.CbCFormula;
import edu.kit.kastel.tva.eebc.lang.ast.BinOp;
import edu.kit.kastel.tva.eebc.lang.ast.Statement;
import edu.kit.kastel.tva.eebc.lang.transformation.CbCModelReader;
import edu.kit.kastel.tva.eebc.lang.transformation.Transformer;
import edu.kit.kastel.tva.eebc.typesystem.ComponentState;
import edu.kit.kastel.tva.eebc.typesystem.ProgramState;
import edu.kit.kastel.tva.eebc.typesystem.State;
import edu.kit.kastel.tva.eebc.typesystem.StatementType;
import edu.kit.kastel.tva.eebc.typesystem.TestHardwareModel;
import edu.kit.kastel.tva.eebc.typesystem.TypingVisitor;

import java.io.File;

public class Demo {
    public static void main(String[] args) throws Exception {
        // check that at least one argument is given (the path to the model file)
        if (args.length < 1) {
            System.err.println("usage: Demo <path/to/example.cbcmodel | path/to/webcorc-program.json> [var=value ...]");
            System.exit(1);
        }

        // load the model file
        File modelFile = new File(args[0]);
        CbCFormula formula = CbCModelReader.readModelFile(modelFile);
        Statement statement = Transformer.transform(formula);

        // initialize the program state with the given variable bindings (used to see that it does not do worst case)
        ProgramState programState = new ProgramState();
        for (int i = 1; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            if (kv.length != 2) {
                System.err.println("skipping malformed initial binding: " + args[i]);
                continue;
            }
            programState.setVariable(kv[0], Integer.parseInt(kv[1]));
        }
        State before = new State(programState, ComponentState.empty());

        // initialize the hardware model
        TestHardwareModel hardware = new TestHardwareModel();
        hardware.setTimeConst(1);
        hardware.setTimeVar(1);
        hardware.setTimeVarAssignment(1);
        hardware.setTimeIf(1);
        for (BinOp.Op op : BinOp.Op.values()) {
            hardware.putTimeBinOp(op, 1);
        }

        // run the statement through the typing visitor
        TypingVisitor typingVisitor = new TypingVisitor(hardware);
        StatementType type = statement.accept(typingVisitor);

        // print the results
        System.out.println("model:         " + modelFile.getName());
        System.out.println("initial state: " + before);
        System.out.println("post state:    " + type.s(before));
        System.out.println("energy cost:   " + type.e(before));
        System.out.println("typing state:  " + type.typingState(before));
    }
}