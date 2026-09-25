package edu.kit.kastel.tva.eebc.verifier.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.kit.kastel.tva.eebc.verifier.analysis.AnalysisReport;
import edu.kit.kastel.tva.eebc.verifier.analysis.EnergyAnalysis;
import edu.kit.kastel.tva.eebc.verifier.analysis.HardwareModels;
import edu.kit.kastel.tva.eebc.verifier.analysis.InvalidRequestException;
import edu.kit.kastel.tva.eebc.verifier.analysis.StatementResult;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The EEbC Verifier: the WebCorC Verifier API (Self-Description, start job, status stream, result) over HTTP and
 * WebSocket on one port.
 * <p>
 * Jobs are kept in memory, keyed by the job id WebCorC chose, and analysed on a fixed pool of threads with a large
 * stack. A job is deleted once its result has been fetched, or by a background sweep once it has been finished and
 * unfetched for longer than the retention time.
 */
public final class VerifierServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(VerifierServer.class);
    private static final String PROBLEM_JSON = "application/problem+json";
    // explicit charset: Jetty would otherwise encode unknown content types as ISO-8859-1
    private static final String PROBLEM_JSON_UTF8 = PROBLEM_JSON + "; charset=utf-8";
    private static final String JSON_UTF8 = "application/json; charset=utf-8";

    private final Config config;
    private final HardwareModels registry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final ScheduledExecutorService sweeper;
    private final Javalin app;

    public VerifierServer(Config config) {
        this(config, HardwareModels.standard());
    }

    public VerifierServer(Config config, HardwareModels registry) {
        this.config = config;
        this.registry = registry;
        AtomicInteger workerCount = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(config.threads(), task -> {
            Thread thread = new Thread(null, task, "eebc-analysis-" + workerCount.incrementAndGet(),
                    config.stackBytes());
            thread.setDaemon(true);
            return thread;
        });
        this.sweeper = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "eebc-job-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        this.app = createApp();
    }

    /** Starts listening. @return this server */
    public VerifierServer start() {
        app.start(config.host(), config.port());
        long sweepMillis = config.sweepInterval().toMillis();
        sweeper.scheduleWithFixedDelay(this::sweep, sweepMillis, sweepMillis, TimeUnit.MILLISECONDS);
        LOG.info("EEbC Verifier listening on {}:{} ({} worker threads, {} MB analysis stack, job retention {})",
                config.host(), port(), config.threads(), config.stackBytes() / (1024 * 1024), config.jobTtl());
        return this;
    }

    /** @return the port the server listens on */
    public int port() {
        return app.port();
    }

    @Override
    public void close() {
        app.stop();
        sweeper.shutdownNow();
        workers.shutdownNow();
    }

    private Javalin createApp() {
        Javalin javalin = Javalin.create(config -> {
            config.showJavalinBanner = false;
            // WebCorC waits for done without a time limit, so the stream must not time out while a job runs
            config.jetty.modifyWebSocketServletFactory(factory -> factory.setIdleTimeout(Duration.ofDays(7)));
        });

        javalin.get("/description", this::description);
        javalin.post("/jobs/{id}", this::startJob);
        javalin.get("/jobs/{id}/result", this::result);
        javalin.wsBeforeUpgrade("/jobs/{id}", ctx -> {
            Job job = jobs.get(ctx.pathParam("id"));
            if (job == null) {
                throw new ProblemException(404, "Unknown job '" + ctx.pathParam("id") + "'");
            }
            if (!job.claimStream()) {
                throw new ProblemException(409, "Job '" + job.id() + "' already has a status stream");
            }
        });
        javalin.ws("/jobs/{id}", ws -> {
            ws.onConnect(ctx -> {
                Job job = jobs.get(ctx.pathParam("id"));
                if (job == null) { // deleted between handshake and connect
                    ctx.closeSession(1011, "unknown job");
                    return;
                }
                job.attach(ctx);
            });
            ws.onClose(ctx -> {
                Job job = jobs.get(ctx.pathParam("id"));
                if (job != null) {
                    job.detach(ctx);
                }
            });
            ws.onError(ctx -> LOG.debug("Status stream error for job {}", ctx.pathParam("id"), ctx.error()));
        });

        javalin.exception(ProblemException.class, (e, ctx) -> problem(ctx, e.status(), e.getMessage()));
        javalin.exception(Exception.class, (e, ctx) -> {
            LOG.error("Internal error on {} {}", ctx.method(), ctx.path(), e);
            problem(ctx, 500, "Internal error of the EEbC Verifier");
        });
        javalin.error(404, ctx -> {
            if (!isProblem(ctx)) {
                problem(ctx, 404, "Not found: " + ctx.path());
            }
        });
        javalin.error(405, ctx -> {
            if (!isProblem(ctx)) {
                problem(ctx, 405, "Method not allowed: " + ctx.method() + " " + ctx.path());
            }
        });
        return javalin;
    }

    private void description(Context ctx) throws JsonProcessingException {
        ctx.contentType(JSON_UTF8).result(mapper.writeValueAsString(SelfDescription.build(mapper, registry)));
    }

    private void startJob(Context ctx) {
        String id = ctx.pathParam("id");
        JsonNode body;
        try {
            body = mapper.readTree(ctx.body());
        } catch (JsonProcessingException e) {
            throw new ProblemException(400, "Request body is not valid JSON");
        }
        if (body == null || !body.isObject()) {
            throw new ProblemException(400, "Request body must be a JSON object");
        }
        for (String field : new String[]{"program", "files", "settings"}) {
            if (!body.hasNonNull(field)) {
                throw new ProblemException(400, "Request is missing '" + field + "'");
            }
        }
        if (!body.get("files").isArray()) {
            throw new ProblemException(400, "'files' must be an array");
        }

        EnergyAnalysis analysis;
        try {
            analysis = EnergyAnalysis.prepare(body.get("program"), body.get("settings"), registry);
        } catch (InvalidRequestException e) {
            throw new ProblemException(400, e.getMessage());
        }

        Job job = new Job(id);
        if (jobs.putIfAbsent(id, job) != null) {
            throw new ProblemException(409, "A job with id '" + id + "' already exists");
        }
        workers.execute(() -> runJob(job, analysis));
        ctx.status(202);
    }

    private void runJob(Job job, EnergyAnalysis analysis) {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode done = mapper.createObjectNode().put("type", "done");
        try {
            AnalysisReport report = analysis.run(line -> job.emit(logMessage(line)));
            for (Map.Entry<Integer, StatementResult> entry : report.statements().entrySet()) {
                result.putObject(String.valueOf(entry.getKey()))
                        .put("proven", entry.getValue().proven())
                        .put("status", entry.getValue().status());
            }
            done.put("proven", report.proven()).put("status", report.status());
        } catch (Throwable t) { // never leave a stream without done
            LOG.error("Job {} failed unexpectedly", job.id(), t);
            result.removeAll();
            for (int statementId : analysis.statementIds()) {
                result.putObject(String.valueOf(statementId))
                        .put("proven", false)
                        .put("status", "not analysed: analysis failed (internal error)");
            }
            try {
                job.emit(logMessage("Analysis failed: internal error"));
            } catch (RuntimeException ignored) {
                // done was already sent
            }
            done.put("proven", false).put("status", "total energy estimate unavailable");
        }
        try {
            job.finish(mapper.writeValueAsString(result), mapper.writeValueAsString(done));
        } catch (JsonProcessingException | RuntimeException e) {
            LOG.error("Job {} could not be finished", job.id(), e);
        }
    }

    private String logMessage(String line) {
        try {
            return mapper.writeValueAsString(mapper.createObjectNode().put("type", "log").put("message", line));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void result(Context ctx) {
        String id = ctx.pathParam("id");
        Job job = jobs.get(id);
        if (job == null) {
            throw new ProblemException(404, "Unknown job '" + id + "'");
        }
        String result = job.result();
        if (result == null) {
            throw new ProblemException(409, "Job '" + id + "' has not finished yet");
        }
        jobs.remove(id, job);
        ctx.contentType(JSON_UTF8).result(result);
    }

    private void sweep() {
        try {
            Instant now = Instant.now();
            jobs.values().removeIf(job -> job.isExpired(now, config.jobTtl()));
        } catch (RuntimeException e) {
            LOG.warn("Job sweep failed", e);
        }
    }

    private boolean isProblem(Context ctx) {
        String contentType = ctx.res().getContentType();
        return contentType != null && contentType.startsWith(PROBLEM_JSON);
    }

    private void problem(Context ctx, int status, String detail) {
        ObjectNode problem = mapper.createObjectNode()
                .put("type", "about:blank")
                .put("title", title(status))
                .put("status", status)
                .put("detail", detail)
                .put("instance", ctx.path());
        try {
            ctx.status(status).contentType(PROBLEM_JSON_UTF8).result(mapper.writeValueAsString(problem));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String title(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 500 -> "Internal Server Error";
            default -> "Error";
        };
    }

    /** An error answered with {@code application/problem+json}; the message is the user-facing {@code detail}. */
    static final class ProblemException extends RuntimeException {
        private final int status;

        ProblemException(int status, String detail) {
            super(detail);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
