package io.github.perlilja.azurespeech;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An interim result ({@code speech.hypothesis} or {@code speech.fragment}). Many arrive per phrase;
 * a client that only wants final text can ignore them. {@code speakerId} is {@code Unknown} unless
 * {@code diarizeIntermediates} is on.
 */
public record Hypothesis(
        String id,
        String text,
        long offsetTicks,
        long durationTicks,
        String speakerId,
        Phrase.Language primaryLanguage,
        JsonNode raw) {

    static Hypothesis fromJson(JsonNode n) {
        return new Hypothesis(
                Phrase.text(n, "Id"),
                Phrase.text(n, "Text"),
                n.path("Offset").asLong(0),
                n.path("Duration").asLong(0),
                Phrase.text(n, "SpeakerId"),
                Phrase.language(n.get("PrimaryLanguage")),
                n);
    }
}
