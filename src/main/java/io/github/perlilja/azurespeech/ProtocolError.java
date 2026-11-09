package io.github.perlilja.azurespeech;

/**
 * Every failure mode of a session, mapped from upgrade status codes, WebSocket close codes and
 * non-success {@code RecognitionStatus} values (spec section 10). The library never retries a
 * session: replaying audio is the caller's decision, so {@link #kind()} is what a retry policy
 * should look at.
 */
public final class ProtocolError extends RuntimeException {

    public enum Kind {
        /** HTTP 401 on upgrade: bad key or expired token. */
        AUTH,
        /** HTTP 400 on upgrade, close code 1007, or {@code BadRequest} status. */
        BAD_REQUEST,
        /** HTTP 403 or {@code Forbidden} status. */
        FORBIDDEN,
        /** HTTP 429 or {@code TooManyRequests} status. */
        THROTTLED,
        /** Service-side failure: 5xx or {@code Error} status. */
        SERVICE,
        /** Transport failure or unexpected close. */
        CONNECTION,
        /** Session deadline or inactivity timeout exceeded. */
        TIMEOUT
    }

    private static final long serialVersionUID = 1L;

    private final Kind kind;
    private final int code;

    public ProtocolError(Kind kind, String message, int code, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.code = code;
    }

    public ProtocolError(Kind kind, String message) {
        this(kind, message, -1, null);
    }

    public Kind kind() {
        return kind;
    }

    /** HTTP status or WebSocket close code that caused the error, or -1 when not applicable. */
    public int code() {
        return code;
    }

    static ProtocolError fromHttpStatus(int status, Throwable cause) {
        Kind k = switch (status) {
            case 401 -> Kind.AUTH;
            case 400 -> Kind.BAD_REQUEST;
            case 403 -> Kind.FORBIDDEN;
            case 429 -> Kind.THROTTLED;
            default -> status >= 500 ? Kind.SERVICE : Kind.CONNECTION;
        };
        return new ProtocolError(k, "WebSocket upgrade rejected with HTTP " + status, status, cause);
    }

    /** Close codes 1002, 1003, 1007 and 4000 are fatal in the reference SDK; 1007 means bad parameters. */
    static ProtocolError fromCloseCode(int code, String reason) {
        Kind k = code == 1007 ? Kind.BAD_REQUEST : Kind.CONNECTION;
        return new ProtocolError(k, "WebSocket closed with code " + code
                + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")") + " before the turn ended", code, null);
    }
}
