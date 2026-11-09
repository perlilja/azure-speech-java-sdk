package io.github.perlilja.azurespeech;

/**
 * Receives the events of one session. Callbacks run on the WebSocket's receive thread, in wire
 * order; do not block in them. {@link #onEnd()} and {@link #onError(ProtocolError)} are mutually
 * exclusive and each fires at most once.
 */
public interface TranscriptionListener {

    /** {@code turn.start}; keep {@code serviceTag} in logs for support cases. */
    default void onTurnStart(String serviceTag) {
    }

    default void onSpeechStartDetected(long offsetTicks) {
    }

    default void onSpeechEndDetected(long offsetTicks) {
    }

    default void onHypothesis(Hypothesis hypothesis) {
    }

    /**
     * Every phrase except those with an error status (those end the session via {@link #onError}).
     * Includes the {@code EndOfDictation} marker; use {@link Phrase#isRecognized()} to filter.
     */
    default void onPhrase(Phrase phrase) {
    }

    /** {@code turn.end} received after the end of the audio stream was sent: success. */
    default void onEnd() {
    }

    default void onError(ProtocolError error) {
    }
}
