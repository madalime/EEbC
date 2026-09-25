package edu.kit.kastel.tva.eebc.verifier;

import edu.kit.kastel.tva.eebc.verifier.server.Config;
import edu.kit.kastel.tva.eebc.verifier.server.VerifierServer;

/**
 * Starts the EEbC Verifier. Configuration comes from the environment variables {@code VERIFIER_PORT},
 * {@code VERIFIER_HOST}, {@code VERIFIER_THREADS}, {@code VERIFIER_STACK_MB} and {@code VERIFIER_JOB_TTL_MINUTES};
 * an invalid value stops the start with exit code 2.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Config config;
        try {
            config = Config.fromEnvironment(System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println("EEbC Verifier: invalid configuration: " + e.getMessage());
            System.exit(2);
            return;
        }
        VerifierServer server = new VerifierServer(config).start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "eebc-verifier-shutdown"));
    }
}
