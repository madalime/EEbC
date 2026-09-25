package edu.kit.kastel.tva.eebc.verifier.server;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.Map;

/** The environment variables an operator sets, and the fail-fast behaviour on invalid values. */
class ConfigTest {
    @Test
    void defaults() {
        Config config = Config.fromEnvironment(Map.of());
        Assertions.assertEquals(8080, config.port());
        Assertions.assertEquals("0.0.0.0", config.host());
        Assertions.assertEquals(Runtime.getRuntime().availableProcessors(), config.threads());
        Assertions.assertEquals(256L * 1024 * 1024, config.stackBytes());
        Assertions.assertEquals(Duration.ofMinutes(60), config.jobTtl());
    }

    @Test
    void allVariables() {
        Config config = Config.fromEnvironment(Map.of(
                "VERIFIER_PORT", "9090",
                "VERIFIER_HOST", "127.0.0.1",
                "VERIFIER_THREADS", "3",
                "VERIFIER_STACK_MB", "512",
                "VERIFIER_JOB_TTL_MINUTES", "5"));
        Assertions.assertEquals(new Config(9090, "127.0.0.1", 3, 512L * 1024 * 1024, Duration.ofMinutes(5),
                Duration.ofMinutes(1)), config);
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "VERIFIER_PORT            | abc   | VERIFIER_PORT must be an integer between 0 and 65535, got 'abc'",
            "VERIFIER_PORT            | 70000 | VERIFIER_PORT must be an integer between 0 and 65535, got '70000'",
            "VERIFIER_PORT            | -1    | VERIFIER_PORT must be an integer between 0 and 65535, got '-1'",
            "VERIFIER_THREADS         | 0     | VERIFIER_THREADS must be an integer between 1 and 10000, got '0'",
            "VERIFIER_THREADS         | \"\"  | VERIFIER_THREADS must be an integer between 1 and 10000, got ''",
            "VERIFIER_STACK_MB        | 0     | VERIFIER_STACK_MB must be an integer between 1 and 1048576, got '0'",
            "VERIFIER_STACK_MB        | 1.5   | VERIFIER_STACK_MB must be an integer between 1 and 1048576, got '1.5'",
            "VERIFIER_JOB_TTL_MINUTES | 0     | VERIFIER_JOB_TTL_MINUTES must be an integer between 1 and 2147483647, got '0'",
            "VERIFIER_HOST            | \" \" | VERIFIER_HOST must not be blank",
    })
    void invalidValueFailsFast(String variable, String value, String message) {
        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class,
                () -> Config.fromEnvironment(Map.of(variable, value)));
        Assertions.assertEquals(message, e.getMessage());
    }
}
