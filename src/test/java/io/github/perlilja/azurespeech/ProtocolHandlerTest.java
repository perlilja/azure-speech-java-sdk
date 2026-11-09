package io.github.perlilja.azurespeech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class ProtocolHandlerTest {

    private static final String ID = "5f1f6c2c7e0d4e8c9a3b2d1c0f9e8d7c";

    static class Recorder implements TranscriptionListener {
        final List<Phrase> phrases = new ArrayList<>();
        final List<Hypothesis> hypotheses = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        String serviceTag;
        ProtocolError error;

        @Override public void onTurnStart(String tag) { serviceTag = tag; events.add("turnStart"); }
        @Override public void onSpeechStartDetected(long o) { events.add("start:" + o); }
        @Override public void onSpeechEndDetected(long o) { events.add("end:" + o); }
        @Override public void onHypothesis(Hypothesis h) { hypotheses.add(h); }
        @Override public void onPhrase(Phrase p) { phrases.add(p); }
        @Override public void onEnd() { events.add("onEnd"); }
        @Override public void onError(ProtocolError e) { error = e; events.add("onError"); }
    }

    private static SpeechMessage msg(String path, String body) {
        return SpeechMessage.parse("Path: " + path + "\r\nX-RequestId: " + ID
                + "\r\nContent-Type: application/json; charset=utf-8\r\n\r\n" + body);
    }

    /** Loads a recording in the spec's format: {requestId, received:[{headers:{path,...}, body}]}. */
    private static List<SpeechMessage> loadRecording(String resource, String[] requestIdOut) throws IOException {
        try (InputStream in = ProtocolHandlerTest.class.getResourceAsStream(resource)) {
            JsonNode root = new ObjectMapper().readTree(in);
            requestIdOut[0] = root.get("requestId").asText();
            List<SpeechMessage> out = new ArrayList<>();
            for (JsonNode r : root.get("received")) {
                String path = r.at("/headers/path").asText();
                String rid = r.at("/headers/x-requestid").asText(requestIdOut[0]);
                out.add(SpeechMessage.parse("Path: " + path + "\r\nX-RequestId: " + rid + "\r\n\r\n" + r.get("body").asText()));
            }
            return out;
        }
    }

    @Test
    void replayOfRecordedServerMessages() throws Exception {
        String[] rid = new String[1];
        List<SpeechMessage> messages = loadRecording("/replay-simple.json", rid);
        Recorder rec = new Recorder();
        ProtocolHandler h = new ProtocolHandler(rid[0], rec);
        h.configured();
        h.streaming();
        h.endOfStreamSent();
        messages.forEach(h::handle);

        assertTrue(h.completion().isDone());
        h.completion().get();
        assertEquals(ProtocolHandler.State.DONE, h.state());
        assertEquals("d2bf86a2c9a547519f9d912116d4b024", rec.serviceTag);
        assertEquals(1, rec.hypotheses.size());
        assertEquals("welcome", rec.hypotheses.get(0).text());

        // Two Success phrases plus the EndOfDictation marker, in order.
        assertEquals(3, rec.phrases.size());
        Phrase first = rec.phrases.get(0);
        assertEquals(RecognitionStatus.SUCCESS, first.recognitionStatus());
        assertEquals("Welcome to Televox. How can I help?", first.displayText());
        assertEquals(700000, first.offsetTicks());
        assertEquals(27200000, first.durationTicks());
        assertEquals("Guest-1", first.speakerId());
        assertEquals(70, first.offsetMs());
        assertEquals("Guest-2", rec.phrases.get(1).speakerId());
        assertEquals(RecognitionStatus.END_OF_DICTATION, rec.phrases.get(2).recognitionStatus());
        assertNull(rec.phrases.get(2).displayText());
        assertFalse(rec.phrases.get(2).isRecognized());

        // speech.endDetected with an empty body is tolerated; onEnd is last.
        assertEquals(List.of("turnStart", "start:700000", "end:0", "onEnd"), rec.events);
    }

    @Test
    void turnEndBeforeEndOfStreamDoesNotCompleteUntilItIsSent() {
        Recorder rec = new Recorder();
        ProtocolHandler h = new ProtocolHandler(ID, rec);
        h.configured();
        h.streaming();
        h.handle(msg("turn.end", "{}"));
        assertFalse(h.completion().isDone());
        h.endOfStreamSent();
        assertTrue(h.completion().isDone());
        assertEquals(List.of("onEnd"), rec.events);
    }

    @Test
    void messagesForAnotherRequestIdAreIgnored() {
        Recorder rec = new Recorder();
        ProtocolHandler h = new ProtocolHandler(ID, rec);
        h.endOfStreamSent();
        h.handle(SpeechMessage.parse("Path: turn.end\r\nX-RequestId: " + "0".repeat(32) + "\r\n\r\n{}"));
        assertFalse(h.completion().isDone());
        h.handle(SpeechMessage.parse("Path: turn.end\r\nX-RequestId: " + ID.toUpperCase() + "\r\n\r\n{}"));
        assertTrue(h.completion().isDone());
    }

    @Test
    void errorStatusesFailTheSession() {
        record Case(String status, ProtocolError.Kind kind) { }
        for (Case c : List.of(new Case("Error", ProtocolError.Kind.SERVICE),
                new Case("TooManyRequests", ProtocolError.Kind.THROTTLED),
                new Case("BadRequest", ProtocolError.Kind.BAD_REQUEST),
                new Case("Forbidden", ProtocolError.Kind.FORBIDDEN))) {
            Recorder rec = new Recorder();
            ProtocolHandler h = new ProtocolHandler(ID, rec);
            h.handle(msg("speech.phrase", "{\"RecognitionStatus\":\"" + c.status + "\"}"));
            assertEquals(c.kind, rec.error.kind(), c.status);
            ExecutionException e = assertThrows(ExecutionException.class, () -> h.completion().get());
            assertSame(rec.error, e.getCause());
            assertTrue(rec.phrases.isEmpty());
        }
    }

    @Test
    void noMatchAndSilenceTimeoutsAreDeliveredNotFatal() {
        Recorder rec = new Recorder();
        ProtocolHandler h = new ProtocolHandler(ID, rec);
        h.handle(msg("speech.phrase", "{\"RecognitionStatus\":\"InitialSilenceTimeout\"}"));
        h.handle(msg("speech.phrase", "{\"RecognitionStatus\":\"NoMatch\"}"));
        assertEquals(2, rec.phrases.size());
        assertFalse(h.completion().isDone());
    }

    @Test
    void recognitionStatusAcceptsNumbersAndUnknownValues() throws Exception {
        ObjectMapper m = new ObjectMapper();
        assertEquals(RecognitionStatus.SUCCESS, RecognitionStatus.fromJson(m.readTree("0")));
        assertEquals(RecognitionStatus.END_OF_DICTATION, RecognitionStatus.fromJson(m.readTree("5")));
        assertEquals(RecognitionStatus.UNKNOWN, RecognitionStatus.fromJson(m.readTree("99")));
        assertEquals(RecognitionStatus.UNKNOWN, RecognitionStatus.fromJson(m.readTree("\"SomethingNew\"")));
        assertEquals(RecognitionStatus.UNKNOWN, RecognitionStatus.fromJson(null));
    }

    @Test
    void detailedPhraseParsesNBestAndLanguage() {
        Recorder rec = new Recorder();
        ProtocolHandler h = new ProtocolHandler(ID, rec);
        h.handle(msg("speech.phrase", "{\"RecognitionStatus\":\"Success\",\"DisplayText\":\"Hi.\",\"Offset\":1,\"Duration\":2,"
                + "\"PrimaryLanguage\":{\"Language\":\"en-US\",\"Confidence\":\"High\"},\"UnknownField\":true,"
                + "\"NBest\":[{\"Confidence\":0.83,\"Lexical\":\"hi\",\"ITN\":\"hi\",\"MaskedITN\":\"hi\",\"Display\":\"Hi.\","
                + "\"Words\":[{\"Word\":\"hi\",\"Offset\":1,\"Duration\":2}]}]}"));
        Phrase p = rec.phrases.get(0);
        assertEquals("en-US", p.primaryLanguage().language());
        assertEquals("High", p.primaryLanguage().confidence());
        assertNull(p.speakerId());
        assertEquals(1, p.nBest().size());
        assertEquals(0.83, p.nBest().get(0).confidence());
        assertEquals("hi", p.nBest().get(0).words().get(0).word());
        assertEquals(true, p.raw().get("UnknownField").asBoolean());
    }

    @Test
    void throwingListenerFailsTheSessionInsteadOfWedgingIt() {
        ProtocolHandler h = new ProtocolHandler(ID, new TranscriptionListener() {
            @Override public void onPhrase(Phrase p) { throw new IllegalStateException("boom"); }
        });
        h.handle(msg("speech.phrase", "{\"RecognitionStatus\":\"Success\"}"));
        assertTrue(h.completion().isCompletedExceptionally());
    }

    @Test
    void closeCodeMapping() {
        assertEquals(ProtocolError.Kind.BAD_REQUEST, ProtocolError.fromCloseCode(1007, "").kind());
        assertEquals(ProtocolError.Kind.CONNECTION, ProtocolError.fromCloseCode(1002, "").kind());
        assertEquals(ProtocolError.Kind.AUTH, ProtocolError.fromHttpStatus(401, null).kind());
        assertEquals(ProtocolError.Kind.BAD_REQUEST, ProtocolError.fromHttpStatus(400, null).kind());
        assertEquals(ProtocolError.Kind.FORBIDDEN, ProtocolError.fromHttpStatus(403, null).kind());
        assertEquals(ProtocolError.Kind.THROTTLED, ProtocolError.fromHttpStatus(429, null).kind());
        assertEquals(ProtocolError.Kind.SERVICE, ProtocolError.fromHttpStatus(503, null).kind());
    }
}
