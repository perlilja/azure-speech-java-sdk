package io.github.perlilja.azurespeech;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One protocol message in either direction: a block of {@code Name: value} headers (CRLF separated)
 * plus a text or binary body (spec section 5).
 *
 * <p>Text frame: {@code headers + CRLF + body}. Binary frame: 2-byte big-endian header length,
 * header block, body bytes.
 */
final class SpeechMessage {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final String CRLF = "\r\n";

    /** Header names, lower-cased (names are case-insensitive on receive). */
    private final Map<String, String> headers;
    private final String text;
    private final byte[] binary;

    private SpeechMessage(Map<String, String> headers, String text, byte[] binary) {
        this.headers = headers;
        this.text = text;
        this.binary = binary;
    }

    static SpeechMessage text(String path, String requestId, String contentType, String body, Instant now) {
        return new SpeechMessage(clientHeaders(path, requestId, contentType, now), body == null ? "" : body, null);
    }

    static SpeechMessage binary(String path, String requestId, String contentType, byte[] body, Instant now) {
        return new SpeechMessage(clientHeaders(path, requestId, contentType, now), null, body == null ? new byte[0] : body);
    }

    private static Map<String, String> clientHeaders(String path, String requestId, String contentType, Instant now) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("path", path);
        h.put("x-requestid", requestId);
        h.put("x-timestamp", TIMESTAMP.format(now));
        if (contentType != null) {
            h.put("content-type", contentType);
        }
        return h;
    }

    // ---- accessors ------------------------------------------------------------------------------------------

    String header(String name) {
        return headers.get(name.toLowerCase());
    }

    String path() {
        return header("path");
    }

    String requestId() {
        return header("x-requestid");
    }

    String timestamp() {
        return header("x-timestamp");
    }

    String contentType() {
        return header("content-type");
    }

    Map<String, String> headers() {
        return Map.copyOf(headers);
    }

    /** Text body, or {@code null} for a binary message. */
    String textBody() {
        return text;
    }

    /** Binary body, or {@code null} for a text message. */
    byte[] binaryBody() {
        return binary;
    }

    boolean isBinary() {
        return binary != null;
    }

    // ---- encoding -------------------------------------------------------------------------------------------

    /** The header block as sent: every header followed by CRLF (no blank line). */
    String headerBlock() {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, "Path", "path");
        appendHeader(sb, "X-RequestId", "x-requestid");
        appendHeader(sb, "X-Timestamp", "x-timestamp");
        appendHeader(sb, "Content-Type", "content-type");
        return sb.toString();
    }

    private void appendHeader(StringBuilder sb, String name, String key) {
        String v = headers.get(key);
        if (v != null) {
            sb.append(name).append(": ").append(v).append(CRLF);
        }
    }

    /** Text frame: header block, blank line, body. */
    String encodeText() {
        return headerBlock() + CRLF + (text == null ? "" : text);
    }

    /** Binary frame: unsigned 16-bit big-endian header length, header block (ASCII), body. */
    byte[] encodeBinary() {
        byte[] head = headerBlock().getBytes(StandardCharsets.US_ASCII);
        if (head.length > 0xFFFF) {
            throw new IllegalStateException("header block too long: " + head.length);
        }
        byte[] body = binary == null ? new byte[0] : binary;
        ByteBuffer buf = ByteBuffer.allocate(2 + head.length + body.length);
        buf.putShort((short) head.length).put(head).put(body);
        return buf.array();
    }

    // ---- parsing --------------------------------------------------------------------------------------------

    /** Parses a text frame. Without a blank line the whole input is treated as headers. */
    static SpeechMessage parse(CharSequence frame) {
        String s = frame.toString();
        int split = s.indexOf(CRLF + CRLF);
        String head;
        String body;
        if (split < 0) {
            head = s;
            body = "";
        } else {
            head = s.substring(0, split);
            body = s.substring(split + 4);
        }
        return new SpeechMessage(parseHeaders(head), body, null);
    }

    /** Parses a binary frame. */
    static SpeechMessage parse(ByteBuffer frame) {
        ByteBuffer b = frame.duplicate();
        if (b.remaining() < 2) {
            throw new IllegalArgumentException("binary frame shorter than its length prefix");
        }
        int headerLength = b.getShort() & 0xFFFF;
        if (b.remaining() < headerLength) {
            throw new IllegalArgumentException("binary frame shorter than its declared header length");
        }
        byte[] head = new byte[headerLength];
        b.get(head);
        byte[] body = new byte[b.remaining()];
        b.get(body);
        return new SpeechMessage(parseHeaders(new String(head, StandardCharsets.US_ASCII)), null, body);
    }

    private static Map<String, String> parseHeaders(String block) {
        Map<String, String> h = new LinkedHashMap<>();
        for (String line : block.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                h.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
        }
        return h;
    }

    @Override
    public String toString() {
        return "SpeechMessage[path=" + path() + ", requestId=" + requestId() + ", "
                + (isBinary() ? binary.length + " bytes" : text.length() + " chars") + "]";
    }
}
