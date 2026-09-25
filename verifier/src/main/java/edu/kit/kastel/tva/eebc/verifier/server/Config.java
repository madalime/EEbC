package edu.kit.kastel.tva.eebc.verifier.server;

import java.time.Duration;
import java.util.Map;

/**
 * Server configuration.
 *
 * @param port          HTTP/WebSocket port ({@code 0}: any free port)
 * @param host          bind address
 * @param threads       size of the analysis worker pool
 * @param stackBytes    stack size of each analysis thread
 * @param jobTtl        how long a finished job whose result was never fetched is kept
 * @param sweepInterval how often expired jobs are looked for
 */
public record Config(int port, String host, int threads, long stackBytes, Duration jobTtl, Duration sweepInterval) {
    public static final String PORT = "VERIFIER_PORT";
    public static final String HOST = "VERIFIER_HOST";
    public static final String THREADS = "VERIFIER_THREADS";
    public static final String STACK_MB = "VERIFIER_STACK_MB";
    public static final String JOB_TTL_MINUTES = "VERIFIER_JOB_TTL_MINUTES";

    public static final int DEFAULT_PORT = 8080;
    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_STACK_MB = 256;
    public static final int DEFAULT_JOB_TTL_MINUTES = 60;

    public Config {
        if (port < 0 || port > 65535 || threads < 1 || stackBytes < 1 || host == null || host.isBlank()
                || jobTtl.isNegative() || jobTtl.isZero() || sweepInterval.isNegative() || sweepInterval.isZero()) {
            throw new IllegalArgumentException("invalid configuration: port " + port + ", host '" + host
                    + "', threads " + threads + ", stack " + stackBytes + " bytes, job TTL " + jobTtl
                    + ", sweep interval " + sweepInterval);
        }
    }

    /**
     * Reads the configuration from environment variables; unset variables take their defaults.
     *
     * @throws IllegalArgumentException naming the variable, for any invalid value
     */
    public static Config fromEnvironment(Map<String, String> env) {
        int port = integer(env, PORT, DEFAULT_PORT, 0, 65535);
        String host = env.getOrDefault(HOST, DEFAULT_HOST);
        if (host.isBlank()) {
            throw new IllegalArgumentException(HOST + " must not be blank");
        }
        int threads = integer(env, THREADS, Runtime.getRuntime().availableProcessors(), 1, 10_000);
        int stackMb = integer(env, STACK_MB, DEFAULT_STACK_MB, 1, 1_048_576);
        int ttlMinutes = integer(env, JOB_TTL_MINUTES, DEFAULT_JOB_TTL_MINUTES, 1, Integer.MAX_VALUE);
        Duration ttl = Duration.ofMinutes(ttlMinutes);
        Duration sweep = ttl.compareTo(Duration.ofMinutes(1)) < 0 ? ttl : Duration.ofMinutes(1);
        return new Config(port, host.strip(), threads, stackMb * 1024L * 1024L, ttl, sweep);
    }

    private static int integer(Map<String, String> env, String name, int defaultValue, int min, int max) {
        String value = env.get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IllegalArgumentException(
                name + " must be an integer between " + min + " and " + max + ", got '" + value + "'");
    }
}
