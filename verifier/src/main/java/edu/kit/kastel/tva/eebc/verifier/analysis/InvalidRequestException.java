package edu.kit.kastel.tva.eebc.verifier.analysis;

/**
 * A job request that cannot be accepted: a structurally broken program or an invalid setting value.
 * The message is written for the end user (it becomes the problem {@code detail} of a {@code 400} response).
 */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
