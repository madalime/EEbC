# Energy Efficiency-by-Construction
This repository contains tool support of the paper *Energy Efficiency-by-Construction* by Kodetzki et al.[^1] submitted to ICTAC 2026.

[^1]: Kodetzki, M., Jarebica, J., Potanin, A., & Schaefer, I.: Energy Efficiency-by-Construction. Submitted 

This repository provides:
- A parser to extract an abstract syntax tree (AST) from a CbC model file created with [CorC](https://github.com/KIT-TVA/CorC).
- An implementation of the energy type system that derives energy types for complete and partial ASTs.

## Requirements
- A Java Development Kit (JDK) 24 of your choice, tested with the [Oracle OpenJDK](https://openjdk.org/).
- A current version of [Maven](https://maven.apache.org/) to build the project.
- [CorC](https://github.com/KIT-TVA/CorC) to create CbC model files.

## Usage
Given a CbC model file at `example.cbcmodel`, the following example will extract the AST from the file:

```java
import de.tu_bs.cs.isf.cbc.cbcmodel.CbCFormula;
import edu.kit.kastel.tva.eebc.lang.ast.Statement;
import edu.kit.kastel.tva.eebc.lang.transformation.CbCModelReader;
import edu.kit.kastel.tva.eebc.lang.transformation.Transformer;
import jakarta.xml.bind.JAXBException;

import java.io.File;
import java.io.IOException;

public class DemoExtraction {
    public static void main(String[] args) throws JAXBException, IOException {
        CbCFormula formula = CbCModelReader.readModelFile(new File("example.cbcmodel"));
        Statement statement = Transformer.transform(formula);
    }
}
```

To derive an energy type for the (partial) AST, a user has to implement their hardware model as implementation
of the interface `edu.kit.kastel.tva.eebc.typesystem.HardwareModel`. For testing, we used a default implementation
located in the test sources.

After implementing a hardware model, one can use the following code to derive an energy type for the AST:

```java
import edu.kit.kastel.tva.eebc.lang.ast.Statement;
import edu.kit.kastel.tva.eebc.typesystem.StatementType;
import edu.kit.kastel.tva.eebc.typesystem.HardwareModel;
import edu.kit.kastel.tva.eebc.typesystem.TypingVisitor;

public class DemoTyping {
    public static void main(String[] args) {
        Statement statement = null; // Obtain the AST from the previous example
        HardwareModel hardwareModel = new MyHardwareModel(); // Replace with your hardware model implementation
        TypingVisitor typingVisitor = new TypingVisitor(hardwareModel);
        StatementType statementType = typingVisitor.visit(statement);
    }
}
```
