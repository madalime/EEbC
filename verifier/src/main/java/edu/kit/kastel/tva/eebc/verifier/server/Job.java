package edu.kit.kastel.tva.eebc.verifier.server;

import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One job: its stream messages (buffered for replay), its result, and its status-stream session.
 * <p>
 * Buffering and sending happen under the job's lock, so a stream that connects while the job is running gets every
 * earlier message exactly once, followed by the live ones; {@code done} is always the last message and is followed
 * by a close with {@code 1000}.
 */
final class Job {
    private static final Logger LOG = LoggerFactory.getLogger(Job.class);
    private static final int CLOSE_NORMAL = 1000;

    private final String id;
    private final List<String> messages = new ArrayList<>();
    private boolean streamClaimed;
    private WsContext stream;
    private boolean finished;
    private Instant finishedAt;
    private String result;

    Job(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    /** Reserves the job's single status stream. @return {@code false} if a stream was already reserved */
    synchronized boolean claimStream() {
        if (streamClaimed) {
            return false;
        }
        streamClaimed = true;
        return true;
    }

    /** Connects the reserved stream: replays all earlier messages, and closes it if the job is already done. */
    synchronized void attach(WsContext context) {
        stream = context;
        for (String message : messages) {
            send(message);
        }
        if (finished) {
            close();
        }
    }

    /** The stream was closed by the client or broke; later messages are only buffered. */
    synchronized void detach(WsContext context) {
        if (stream == context) {
            stream = null;
        }
    }

    /** Buffers a {@code log} message and sends it to the stream, if connected. */
    synchronized void emit(String message) {
        if (finished) {
            throw new IllegalStateException("job " + id + " already sent done");
        }
        messages.add(message);
        send(message);
    }

    /** Stores the result, then sends {@code done} (the last message) and closes the stream, if connected. */
    synchronized void finish(String resultJson, String doneMessage) {
        if (finished) {
            throw new IllegalStateException("job " + id + " already sent done");
        }
        result = resultJson;
        messages.add(doneMessage);
        finished = true;
        finishedAt = Instant.now();
        send(doneMessage);
        close();
    }

    /** @return the result as JSON, or {@code null} if the job has not finished yet */
    synchronized String result() {
        return result;
    }

    synchronized boolean isExpired(Instant now, Duration ttl) {
        return finished && finishedAt.plus(ttl).isBefore(now);
    }

    private void send(String message) {
        if (stream == null) {
            return;
        }
        try {
            stream.send(message);
        } catch (RuntimeException e) {
            LOG.warn("Job {}: status stream broke while sending", id, e);
            stream = null;
        }
    }

    private void close() {
        if (stream == null) {
            return;
        }
        try {
            stream.closeSession(CLOSE_NORMAL, "done");
        } catch (RuntimeException e) {
            LOG.warn("Job {}: could not close status stream", id, e);
        }
        stream = null;
    }
}
