package io.github.perlilja.azurespeech;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SpeechMessageTest {

    private static final String ID = "5f1f6c2c7e0d4e8c9a3b2d1c0f9e8d7c";
    private static final Instant NOW = Instant.parse("2026-11-09T13:30:00.123Z");

    @Test
    void textFrameLayout() {
        String frame = SpeechMessage.text("speech.config", ID, "application/json", "{\"a\":1}", NOW).encodeText();
        assertEquals("Path: speech.config\r\n"
                + "X-RequestId: " + ID + "\r\n"
                + "X-Timestamp: 2026-11-09T13:30:00.123Z\r\n"
                + "Content-Type: application/json\r\n"
                + "\r\n"
                + "{\"a\":1}", frame);
    }

    @Test
    void timestampKeepsMillisecondsWhenZero() {
        String frame = SpeechMessage.text("p", ID, null, "", Instant.parse("2026-11-09T13:30:00Z")).encodeText();
        assertTrue(frame.contains("X-Timestamp: 2026-11-09T13:30:00.000Z\r\n"));
    }

    /** Spec section 5 worked example: 13 + 47 + 39 + 27 = 126 bytes, so the frame starts 00 7E. */
    @Test
    void binaryFrameWorkedExample() {
        byte[] wav = AudioFormat.PCM_16K_MONO.wavHeader();
        byte[] frame = SpeechMessage.binary("audio", ID, "audio/x-wav", wav, NOW).encodeBinary();
        assertEquals(0x00, frame[0]);
        assertEquals(0x7E, frame[1]);
        assertEquals(2 + 126 + 44, frame.length);
        String head = new String(frame, 2, 126, StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("Path: audio\r\nX-RequestId: " + ID + "\r\n"));
        assertTrue(head.endsWith("Content-Type: audio/x-wav\r\n"));
    }

    @Test
    void endOfStreamFrameHasNoContentTypeAndEmptyBody() {
        byte[] frame = SpeechMessage.binary("audio", ID, null, new byte[0], NOW).encodeBinary();
        int headerLength = ((frame[0] & 0xFF) << 8) | (frame[1] & 0xFF);
        assertEquals(2 + headerLength, frame.length);
        assertFalse(new String(frame, 2, headerLength, StandardCharsets.US_ASCII).contains("Content-Type"));
    }

    @Test
    void binaryRoundTrip() {
        byte[] body = {1, 2, 3, (byte) 0xFF};
        SpeechMessage m = SpeechMessage.parse(ByteBuffer.wrap(
                SpeechMessage.binary("audio", ID, "audio/x-wav", body, NOW).encodeBinary()));
        assertEquals("audio", m.path());
        assertEquals(ID, m.requestId());
        assertEquals("audio/x-wav", m.contentType());
        assertTrue(m.isBinary());
        assertArrayEquals(body, m.binaryBody());
        assertNull(m.textBody());
    }

    @Test
    void textRoundTripAndBodyContainingBlankLines() {
        String body = "{\"x\":\"a\r\n\r\nb\"}";
        SpeechMessage m = SpeechMessage.parse(SpeechMessage.text("speech.phrase", ID, "application/json", body, NOW).encodeText());
        assertEquals("speech.phrase", m.path());
        assertEquals(body, m.textBody());
    }

    @Test
    void headerNamesAreCaseInsensitiveOnParse() {
        SpeechMessage m = SpeechMessage.parse("PATH: turn.end\r\nx-requestid: " + ID
                + "\r\ncontent-type: application/json; charset=utf-8\r\n\r\n{}");
        assertEquals("turn.end", m.path());
        assertEquals(ID, m.requestId());
        assertEquals("application/json; charset=utf-8", m.contentType());
        assertEquals("{}", m.textBody());
    }

    @Test
    void truncatedBinaryFrameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SpeechMessage.parse(ByteBuffer.wrap(new byte[]{0x00})));
        assertThrows(IllegalArgumentException.class, () -> SpeechMessage.parse(ByteBuffer.wrap(new byte[]{0x00, 0x7E, 1, 2})));
    }
}
