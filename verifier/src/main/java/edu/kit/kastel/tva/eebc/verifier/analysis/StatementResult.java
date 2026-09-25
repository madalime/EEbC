package edu.kit.kastel.tva.eebc.verifier.analysis;

/**
 * The result for one WebCorC statement, as sent in the job result.
 *
 * @param proven whether the statement passed the energy check
 * @param status the text shown on the statement, e.g. {@code energy estimate: 12 / 50}
 */
public record StatementResult(boolean proven, String status) {
}
