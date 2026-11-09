package io.github.perlilja.azurespeech;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletableFuture;

/**
 * The receive side of a session as a transport-independent state machine: feed it server messages
 * and it drives the listener and decides when the session is done. The only success exit is
 * {@code turn.end} after the end-of-stream frame was sent (spec 7).
 */
final class ProtocolHandler {

    enum State { CONNECTING, CONFIGURED, STREAMING, DRAINING, DONE, FAILED }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(ProtocolHandler.class.getName());

    private final String requestId;
    private final TranscriptionListener listener;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    private State state = State.CONNECTING;
    private boolean turnEnded;

    ProtocolHandler(String requestId, TranscriptionListener listener) {
        this.requestId = requestId;
        this.listener = listener;
    }

    CompletableFuture<Void> completion() {
        return completion;
    }

    synchronized State state() {
        return state;
    }

    // ---- sender-side transitions ---------------------------------------------------------------------------

    synchronized void configured() {
        advance(State.CONFIGURED);
    }

    synchronized void streaming() {
        advance(State.STREAMING);
    }

    /** The empty end-of-stream frame went out; from here a {@code turn.end} completes the session. */
    void endOfStreamSent() {
        boolean finish;
        synchronized (this) {
            advance(State.DRAINING);
            finish = turnEnded && state == State.DRAINING;
        }
        if (finish) {
            succeed();
        }
    }

    private void advance(State next) {
        if (state != State.DONE && state != State.FAILED && next.ordinal() > state.ordinal()) {
            state = next;
        }
    }

    // ---- receive side ----------------------------------------------------------------------------------------

    void handle(SpeechMessage m) {
        if (completion.isDone()) {
            return;
        }
        if (m.requestId() != null && !m.requestId().equalsIgnoreCase(requestId)) {
            LOG.log(System.Logger.Level.DEBUG, "ignoring message for another request id: {0}", m.path());
            return;
        }
        String path = m.path();
        if (path == null) {
            return;
        }
        try {
            switch (path.toLowerCase()) {
                case "turn.start" -> {
                    JsonNode body = json(m);
                    JsonNode tag = body.path("context").path("serviceTag");
                    String serviceTag = tag.isMissingNode() ? null : tag.asText();
                    LOG.log(System.Logger.Level.DEBUG, "turn.start serviceTag={0}", serviceTag);
                    listener.onTurnStart(serviceTag);
                }
                case "speech.startdetected" ->
                        listener.onSpeechStartDetected(json(m).path("Offset").asLong(0));
                case "speech.enddetected" ->
                        listener.onSpeechEndDetected(json(m).path("Offset").asLong(0)); // body may be empty
                case "speech.hypothesis", "speech.fragment" ->
                        listener.onHypothesis(Hypothesis.fromJson(json(m)));
                case "speech.phrase" -> phrase(Phrase.fromJson(json(m)));
                case "turn.end" -> turnEnd();
                default -> LOG.log(System.Logger.Level.DEBUG, "ignoring message {0}", path);
            }
        } catch (RuntimeException e) {
            // A listener that throws must not wedge the session.
            fail(new ProtocolError(ProtocolError.Kind.SERVICE, "failed to process " + path + ": " + e.getMessage(), -1, e));
        }
    }

    private void phrase(Phrase p) {
        ProtocolError.Kind kind = switch (p.recognitionStatus()) {
            case ERROR -> ProtocolError.Kind.SERVICE;
            case TOO_MANY_REQUESTS -> ProtocolError.Kind.THROTTLED;
            case BAD_REQUEST -> ProtocolError.Kind.BAD_REQUEST;
            case FORBIDDEN -> ProtocolError.Kind.FORBIDDEN;
            default -> null;
        };
        if (kind != null) {
            fail(new ProtocolError(kind, "speech.phrase with RecognitionStatus " + p.recognitionStatus()));
            return;
        }
        listener.onPhrase(p);
    }

    private void turnEnd() {
        boolean finish;
        synchronized (this) {
            turnEnded = true;
            finish = state == State.DRAINING;
        }
        if (finish) {
            succeed();
        }
    }

    private static JsonNode json(SpeechMessage m) {
        String body = m.textBody();
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("malformed JSON in " + m.path(), e);
        }
    }

    // ---- terminal transitions --------------------------------------------------------------------------------

    void succeed() {
        synchronized (this) {
            if (state == State.DONE || state == State.FAILED) {
                return;
            }
            state = State.DONE;
        }
        try {
            listener.onEnd();
        } finally {
            completion.complete(null);
        }
    }

    void fail(ProtocolError error) {
        synchronized (this) {
            if (state == State.DONE || state == State.FAILED) {
                return;
            }
            state = State.FAILED;
        }
        try {
            listener.onError(error);
        } finally {
            completion.completeExceptionally(error);
        }
    }
}
