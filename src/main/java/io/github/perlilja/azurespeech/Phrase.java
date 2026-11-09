package io.github.perlilja.azurespeech;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@code speech.phrase} message. Offsets and durations are 100-nanosecond ticks relative to the
 * start of the audio (10 000 ticks per millisecond). Parsing is lenient: unknown fields are ignored
 * and absent ones become {@code null}/0; the full message is kept in {@link #raw()}.
 *
 * @param speakerId        {@code Guest-1}, {@code Guest-2}, ... with diarization; {@code Unknown} on the
 *                         end marker; {@code null} when the context did not ask for diarization
 * @param displayText      {@code null} on the {@code EndOfDictation} marker
 * @param primaryLanguage  only present with language identification
 * @param nBest            only present with detailed output
 */
public record Phrase(
        String id,
        RecognitionStatus recognitionStatus,
        String displayText,
        long offsetTicks,
        long durationTicks,
        int channel,
        String speakerId,
        Language primaryLanguage,
        List<NBest> nBest,
        String srModel,
        JsonNode raw) {

    public record Language(String language, String confidence) {
    }

    public record Word(String word, long offsetTicks, long durationTicks, Double confidence) {
    }

    public record NBest(Double confidence, String lexical, String itn, String maskedItn, String display, List<Word> words) {
    }

    /** True for a phrase that carries recognised text ({@code Success}). */
    public boolean isRecognized() {
        return recognitionStatus == RecognitionStatus.SUCCESS;
    }

    public long offsetMs() {
        return offsetTicks / 10_000;
    }

    public long durationMs() {
        return durationTicks / 10_000;
    }

    static Phrase fromJson(JsonNode n) {
        return new Phrase(
                text(n, "Id"),
                RecognitionStatus.fromJson(n.get("RecognitionStatus")),
                text(n, "DisplayText"),
                n.path("Offset").asLong(0),
                n.path("Duration").asLong(0),
                n.path("Channel").asInt(0),
                text(n, "SpeakerId"),
                language(n.get("PrimaryLanguage")),
                nBest(n.get("NBest")),
                text(n, "SRModel"),
                n);
    }

    static Language language(JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        return new Language(text(n, "Language"), text(n, "Confidence"));
    }

    private static List<NBest> nBest(JsonNode arr) {
        List<NBest> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode e : arr) {
                List<Word> words = new ArrayList<>();
                JsonNode w = e.get("Words");
                if (w != null && w.isArray()) {
                    for (JsonNode x : w) {
                        words.add(new Word(text(x, "Word"), x.path("Offset").asLong(0), x.path("Duration").asLong(0),
                                x.hasNonNull("Confidence") ? x.get("Confidence").asDouble() : null));
                    }
                }
                out.add(new NBest(e.hasNonNull("Confidence") ? e.get("Confidence").asDouble() : null,
                        text(e, "Lexical"), text(e, "ITN"), text(e, "MaskedITN"), text(e, "Display"), List.copyOf(words)));
            }
        }
        return List.copyOf(out);
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
