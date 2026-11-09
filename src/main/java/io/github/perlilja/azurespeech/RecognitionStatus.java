package io.github.perlilja.azurespeech;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code RecognitionStatus} of a {@code speech.phrase} (spec 9.4). Accepts the string or the numeric form. */
public enum RecognitionStatus {
    SUCCESS,
    NO_MATCH,
    INITIAL_SILENCE_TIMEOUT,
    BABBLE_TIMEOUT,
    ERROR,
    END_OF_DICTATION,
    TOO_MANY_REQUESTS,
    BAD_REQUEST,
    FORBIDDEN,
    /** Anything this library does not know; the raw JSON is still available on the phrase. */
    UNKNOWN;

    /**
     * Numeric codes follow the declaration order above (the order of the reference JavaScript SDK's
     * enum). The numeric form has not been observed on the wire, so this mapping is unverified.
     */
    static RecognitionStatus fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return UNKNOWN;
        }
        if (node.isNumber()) {
            int i = node.asInt();
            return i >= 0 && i < UNKNOWN.ordinal() ? values()[i] : UNKNOWN;
        }
        String s = node.asText().replace("_", "").toLowerCase();
        return switch (s) {
            case "success" -> SUCCESS;
            case "nomatch" -> NO_MATCH;
            case "initialsilencetimeout" -> INITIAL_SILENCE_TIMEOUT;
            case "babbletimeout" -> BABBLE_TIMEOUT;
            case "error" -> ERROR;
            case "endofdictation" -> END_OF_DICTATION;
            case "toomanyrequests" -> TOO_MANY_REQUESTS;
            case "badrequest" -> BAD_REQUEST;
            case "forbidden" -> FORBIDDEN;
            default -> UNKNOWN;
        };
    }
}
