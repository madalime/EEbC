package edu.kit.kastel.tva.eebc.lang.transformation;

/**
 * Thrown by {@link CbCModelReader#readProgram(String)} when a WebCorC {@code program} is structurally broken:
 * an unknown or missing statement {@code type}, a missing or non-integer {@code id}, a duplicate {@code id},
 * missing children ({@code firstStatement}, {@code secondStatement}, {@code loopStatement}, {@code commands}),
 * a missing {@code guard}/{@code guards} condition, or {@code guards} and {@code commands} of different length.
 * <p>
 * The message is written for the end user and already names the statement, e.g.
 * {@code Statement 'Loop' (id 3): selection has 2 guards but 1 commands}.
 * Errors that do not belong to a single statement (e.g. a missing root {@code statement}) have
 * {@link #statementId()} and {@link #statementName()} {@code null} and start with {@code Program: }.
 */
public class InvalidProgramException extends IllegalArgumentException {
    private final Integer statementId;
    private final String statementName;

    public InvalidProgramException(Integer statementId, String statementName, String problem) {
        super(prefix(statementId, statementName) + problem);
        this.statementId = statementId;
        this.statementName = statementName;
    }

    private static String prefix(Integer statementId, String statementName) {
        if (statementId == null && statementName == null) {
            return "Program: ";
        }
        StringBuilder prefix = new StringBuilder("Statement");
        if (statementName != null) {
            prefix.append(" '").append(statementName).append('\'');
        }
        if (statementId != null) {
            prefix.append(statementName == null ? " id " : " (id ").append(statementId);
            if (statementName != null) {
                prefix.append(')');
            }
        }
        return prefix.append(": ").toString();
    }

    /**
     * @return the id of the offending statement, or {@code null} if it has no valid id or the problem is not
     * tied to a single statement
     */
    public Integer statementId() {
        return statementId;
    }

    /**
     * @return the name of the offending statement, or {@code null} if the problem is not tied to a single statement
     */
    public String statementName() {
        return statementName;
    }
}
