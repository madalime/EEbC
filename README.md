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

## EEbC Verifier for WebCorC
The `verifier` module is an HTTP/WebSocket service that implements the
[WebCorC](https://github.com/KIT-TVA/WebCorC) Verifier API (version 1.0: Self-Description, start job, status stream,
result). It lets WebCorC users see and bound the energy of their programs, statement by statement, next to the
functional verification result.

The Verifier is a thin layer **on top of** EEbC. It defines no energy semantics of its own. It runs EEbC exactly like
the demo does (translate, type with `TypingVisitor`, evaluate the root from the start state) and only observes that
run. For every statement (subtree), it reports the energy EEbC computed and checks it against a budget.

### Settings (per WebCorC project)
The Verifier is **disabled by default** and appears in WebCorC as *Energy (EEbC)*.

| Setting | Id | Default | Meaning |
|---|---|---|---|
| Energy upper bound | `upperBound` | `0` | A whole number ≥ 0. A statement passes if its energy estimate is ≤ the bound. |
| Hardware model | `hardwareModel` | `unit` | The cost model. `unit`: every operation costs 1, energy = time (the demo's configuration). |
| Initial variable values | `initialState` | empty | The start state of the analysed run (see below). |

Invalid values (for example an upper bound of `-1` or `2.5`, an unknown hardware model, or malformed initial values)
are rejected with `400` and a message quoting the problem. Missing settings take their defaults, and unknown settings
are ignored. New hardware models are added by registering them in `HardwareModels.standard()`. They then appear in
WebCorC's selection automatically.

#### `initialState` grammar
```
initialState := ε | pair ("," pair)* ","?        -- whitespace allowed around every token
pair         := name "=" value
name         := [A-Za-z_][A-Za-z0-9_]*
value        := 32-bit signed integer, e.g. 10, -5, +3
```
Example: `n=10, i=0`. Variables that are not listed start at 0, so an empty value means that all variables start
at 0. A name that is given twice, or malformed input, is rejected (e.g. `Initial state: expected name=integer at
'n==10'`). A name that is not one of the program's `javaVariables` is accepted with a warning in the console.

### Results
Every statement gets `proven` and a status:

| Case | proven | status |
|---|---|---|
| Executed, within bound | true | `energy estimate: 12 / 50` |
| Executed, over bound | false | `energy estimate: 62 / 50 (exceeds bound)` |
| Executed several times (in a loop) | by the maximum | `energy estimate: max 12 / 50` |
| Not executed | true | `not executed for this input` |
| Unrefined leaf (empty code) | false | `energy estimate incomplete: statement not yet refined` |
| Contains unrefined parts | false | `energy estimate incomplete: 17 / 50 (contains unrefined statements)` |
| Loop without variant | false | `not analysed: loop has no variant` |
| Code EEbC does not support | false | `not analysed: unsupported code: <reason>` |
| Contains an unanalysable statement | false | `not analysed: contains statement '<name>' that could not be analysed` |
| Runs after (or inside) an unanalysable statement | false | `not analysed: start state unknown (after '<name>')` |
| Runtime failure (e.g. division by zero) | false | `not analysed: analysis failed (<reason>)`, on every statement |
| Integer overflow (negative energy) | false | `energy estimate overflowed` |

The program root (the `done` message) is proven iff every statement is proven. Its status is
`total energy estimate: <energy> / <bound>`, or `total energy estimate unavailable` if the root has no number.
Energy values are EEbC's plain integers, with no unit. Loops are analysed with the variant that the user wrote for
functional verification, as EEbC's iteration count.

The console shows only an opening line (`Hardware model: unit, upper bound: 50, initial state: n=10, i=0`), problem
lines (translation failures, `initialState` warnings, a runtime failure), and a summary
(`<p> proven, <f> failed, <x> not executed`). Stack traces go only to the server log.

### Limitations
- **One concrete run.** EEbC evaluates the program once, from the initial state. Branches and loop bodies that this
  run does not reach are reported as `not executed for this input` and count as passing. A green result therefore
  means **"within budget for this input"**, not "for all inputs". Check other paths by changing the initial values.
- EEbC's Java subset: plain assignments `x = e;` with variables, integer literals and binary operators. `i++`,
  compound assignments, declarations, arrays, method calls, unary operators, parentheses, boolean literals and JML
  (e.g. `\old`) are reported as unsupported code.
- Only `variant`, `guard`/`guards` and the statement structure are used. The `files`, the invariants and all
  pre-, post- and intermediate conditions sent by WebCorC are ignored. The Verifier declares no condition variables.
- Energy is a 32-bit integer. A negative estimate is reported as an overflow, but an overflow that wraps around to a
  positive number cannot be detected.
- EEbC's loop typing state only looks at the variant. The Verifier therefore treats a statement as incomplete when
  any executed statement inside it is incomplete. It derives this from the typing states that EEbC computes for those
  statements.
- Jobs are kept in memory only. There is no authentication or TLS, so deploy the Verifier where only the WebCorC
  backend can reach it (or behind a reverse proxy).

### Running
Build the EEbC library, then the Verifier's fat jar, and start it:
```shell
mvn install -DskipTests                  # in the repository root
mvn -f verifier/pom.xml package          # builds verifier/target/eebc-verifier.jar
java -jar verifier/target/eebc-verifier.jar
```
Or build and run the Docker image (build context: the repository root):
```shell
docker build -f verifier/Dockerfile -t eebc-verifier .
docker run --rm -p 8080:8080 eebc-verifier
```
Register the Verifier in WebCorC under its base URL, e.g. `http://eebc-verifier:8080`. `GET /description` returns
its Self-Description.

| Environment variable | Default | Purpose |
|---|---|---|
| `VERIFIER_PORT` | `8080` | HTTP/WebSocket port |
| `VERIFIER_HOST` | `0.0.0.0` | bind address |
| `VERIFIER_THREADS` | number of CPU cores | size of the analysis worker pool |
| `VERIFIER_STACK_MB` | `256` | stack size of each analysis thread (EEbC evaluates loops recursively, so long loops need a large stack) |
| `VERIFIER_JOB_TTL_MINUTES` | `60` | how long a finished job is kept if its result is never fetched |

The server refuses to start (exit code 2) if a value is invalid. A job is deleted as soon as its result has been
fetched. A duplicate job id, or a second status stream for the same job, is rejected with `409`. All errors are
sent as `application/problem+json`.
