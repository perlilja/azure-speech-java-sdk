package io.github.perlilja.azurespeech;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client for the Azure Speech real-time transcription WebSocket protocol. One call to
 * {@code transcribe} is one session (one WebSocket, one turn). The client does not retry.
 *
 * <p>Logging uses {@link System.Logger} and never includes audio, text, keys or tokens.
 */
public final class SpeechClient implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SpeechClient.class.getName());
    private static final long UNTHROTTLED_MS = 5000; // SPEECH-TransmitLengthBeforThrottleMs default

    private static final HttpClient TOKEN_HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private final SpeechClientConfig config;
    private final Set<ProtocolHandler> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private final HttpClient http;
    private final ExecutorService senders = Executors.newCachedThreadPool(daemon("azure-speech-sender"));
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(daemon("azure-speech-timer"));

    public SpeechClient(SpeechClientConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
    }

    // ---- public API -------------------------------------------------------------------------------------------

    /**
     * Transcribes a RIFF/WAVE file. The file must be PCM; 16 kHz 16-bit mono is the verified format.
     */
    public CompletableFuture<Void> transcribeWav(Path wavFile, TranscriptionListener listener) {
        WavReader.Wav wav;
        try {
            wav = WavReader.open(wavFile);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new ProtocolError(ProtocolError.Kind.BAD_REQUEST,
                    "cannot read WAV file: " + e.getMessage(), -1, e));
        }
        return transcribe(wav.pcm(), wav.format(), listener).whenComplete((v, e) -> wav.close());
    }

    /**
     * Streams raw PCM from {@code pcm} until it ends, then waits for {@code turn.end}. The returned
     * future completes normally on success and exceptionally with a {@link ProtocolError} on failure;
     * the listener is notified either way. The stream is read on a background thread; the caller keeps
     * ownership and closes it.
     */
    public CompletableFuture<Void> transcribe(InputStream pcm, AudioFormat format, TranscriptionListener listener) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("SpeechClient is closed"));
        }
        String connectionId = newId();
        String requestId = newId();
        ProtocolHandler handler = new ProtocolHandler(requestId, listener);
        CompletableFuture<Void> result = handler.completion();
        active.add(handler);
        AtomicReference<Future<?>> senderRef = new AtomicReference<>();

        AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        Runnable touch = () -> lastActivity.set(System.nanoTime());

        AtomicReference<WebSocket> wsRef = new AtomicReference<>();
        Receiver receiver = new Receiver(handler, touch, wsRef);

        WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(config.connectTimeout());
        if (config.subscriptionKey() != null) {
            builder.header("Ocp-Apim-Subscription-Key", config.subscriptionKey());
        } else {
            builder.header("Authorization", "Bearer " + config.authorizationToken());
        }
        builder.header("X-ConnectionId", connectionId);

        URI uri = config.uri();
        LOG.log(System.Logger.Level.DEBUG, "connecting to {0}{1} connectionId={2} requestId={3}",
                uri.getHost(), uri.getPath(), connectionId, requestId);

        ScheduledFuture<?> deadline = timers.schedule(() -> handler.fail(new ProtocolError(ProtocolError.Kind.TIMEOUT,
                "session exceeded " + config.sessionTimeout())), config.sessionTimeout().toMillis(), TimeUnit.MILLISECONDS);
        ScheduledFuture<?> watchdog = timers.scheduleWithFixedDelay(() -> {
            long idleNanos = System.nanoTime() - lastActivity.get();
            if (idleNanos > config.inactivityTimeout().toNanos()) {
                handler.fail(new ProtocolError(ProtocolError.Kind.TIMEOUT,
                        "no activity for " + config.inactivityTimeout()));
            }
        }, 1, 1, TimeUnit.SECONDS);

        result.whenComplete((v, e) -> {
            active.remove(handler);
            deadline.cancel(false);
            watchdog.cancel(false);
            WebSocket ws = wsRef.get();
            if (ws != null) {
                if (e == null) {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "Normal closure by client");
                } else {
                    ws.abort();
                }
            }
            Future<?> sender = senderRef.get();
            if (sender != null && e != null) {
                sender.cancel(true); // unblocks pacing sleeps and interruptible reads
            }
        });

        builder.buildAsync(uri, receiver).whenComplete((ws, err) -> {
            if (err != null) {
                handler.fail(connectError(err));
                return;
            }
            wsRef.set(ws);
            if (result.isDone()) {
                ws.abort();
                return;
            }
            touch.run();
            try {
                senderRef.set(senders.submit(() -> sendAll(ws, handler, requestId, pcm, format, touch)));
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                handler.fail(new ProtocolError(ProtocolError.Kind.CONNECTION, "SpeechClient is closed", -1, rejected));
            }
        });
        return result;
    }

    /**
     * Exchanges a Speech key for a bearer token (valid for about ten minutes) via the regional
     * {@code issueToken} endpoint. Sovereign clouds are not covered; use the key there.
     */
    public static String fetchToken(String region, String subscriptionKey) throws IOException, InterruptedException {
        SpeechClientConfig.checkRegion(region);
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://" + region + ".api.cognitive.microsoft.com/sts/v1.0/issueToken"))
                .timeout(Duration.ofSeconds(30))
                .header("Ocp-Apim-Subscription-Key", subscriptionKey)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = TOKEN_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw ProtocolError.fromHttpStatus(resp.statusCode(), null);
        }
        return resp.body();
    }

    /** Fails every session still in flight with {@link ProtocolError.Kind#CONNECTION}, then stops the client's threads. */
    @Override
    public void close() {
        closed = true;
        for (ProtocolHandler h : active.toArray(ProtocolHandler[]::new)) {
            h.fail(new ProtocolError(ProtocolError.Kind.CONNECTION, "SpeechClient closed during the session"));
        }
        senders.shutdownNow();
        timers.shutdownNow();
    }

    // ---- sending ----------------------------------------------------------------------------------------------

    private void sendAll(WebSocket ws, ProtocolHandler handler, String requestId, InputStream pcm,
                         AudioFormat format, Runnable touch) {
        try {
            sendText(ws, "speech.config", requestId, ClientMessages.json(ClientMessages.speechConfig(config, format)));
            handler.configured();
            sendText(ws, "speech.context", requestId,
                    ClientMessages.json(ClientMessages.speechContext(config, newId())));
            sendBinary(ws, requestId, "audio/x-wav", format.wavHeader());
            handler.streaming();
            touch.run();

            int bytesPerSecond = format.bytesPerSecond();
            int chunkBytes = (int) Math.max(format.channels() * format.bitsPerSample() / 8,
                    bytesPerSecond * config.chunkDuration().toMillis() / 1000);
            chunkBytes -= chunkBytes % Math.max(1, format.channels() * format.bitsPerSample() / 8); // whole samples
            long sentBytes = 0;
            long lastSend = System.nanoTime();
            while (!handler.completion().isDone()) {
                byte[] chunk = pcm.readNBytes(chunkBytes);
                if (chunk.length == 0) {
                    break;
                }
                if (config.pacing() && sentBytes * 1000 / bytesPerSecond >= UNTHROTTLED_MS) {
                    long waitMs = chunk.length * 1000L / (bytesPerSecond * 2L);
                    long elapsedMs = (System.nanoTime() - lastSend) / 1_000_000;
                    if (elapsedMs < waitMs) {
                        Thread.sleep(waitMs - elapsedMs);
                    }
                }
                sendBinary(ws, requestId, null, chunk);
                lastSend = System.nanoTime();
                sentBytes += chunk.length;
                touch.run();
            }
            if (handler.completion().isDone()) {
                return;
            }
            sendBinary(ws, requestId, null, new byte[0]); // end of stream: empty audio frame
            LOG.log(System.Logger.Level.DEBUG, "end of stream sent after {0} bytes", sentBytes);
            handler.endOfStreamSent();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handler.fail(new ProtocolError(ProtocolError.Kind.CONNECTION, "sender interrupted", -1, e));
        } catch (IOException | RuntimeException e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            handler.fail(new ProtocolError(ProtocolError.Kind.CONNECTION, "sending failed: " + cause.getMessage(), -1, cause));
        }
    }

    private static void sendText(WebSocket ws, String path, String requestId, String body) {
        ws.sendText(SpeechMessage.text(path, requestId, "application/json", body, Instant.now()).encodeText(), true).join();
    }

    private static void sendBinary(WebSocket ws, String requestId, String contentType, byte[] body) {
        byte[] frame = SpeechMessage.binary("audio", requestId, contentType, body, Instant.now()).encodeBinary();
        ws.sendBinary(ByteBuffer.wrap(frame), true).join(); // completion is the backpressure signal
    }

    // ---- errors -----------------------------------------------------------------------------------------------

    private static ProtocolError connectError(Throwable err) {
        Throwable t = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        if (t instanceof WebSocketHandshakeException h) {
            return ProtocolError.fromHttpStatus(h.getResponse().statusCode(), t);
        }
        if (t instanceof HttpTimeoutException) {
            return new ProtocolError(ProtocolError.Kind.TIMEOUT, "connect timed out", -1, t);
        }
        return new ProtocolError(ProtocolError.Kind.CONNECTION, "connect failed: " + t, -1, t);
    }

    // ---- receiving --------------------------------------------------------------------------------------------

    private static final class Receiver implements WebSocket.Listener {
        private final ProtocolHandler handler;
        private final Runnable touch;
        private final AtomicReference<WebSocket> wsRef;
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

        Receiver(ProtocolHandler handler, Runnable touch, AtomicReference<WebSocket> wsRef) {
            this.handler = handler;
            this.touch = touch;
            this.wsRef = wsRef;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            touch.run();
            text.append(data);
            if (last) {
                String frame = text.toString();
                text.setLength(0);
                handler.handle(SpeechMessage.parse(frame));
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            touch.run();
            byte[] part = new byte[data.remaining()];
            data.get(part);
            binary.writeBytes(part);
            if (last) {
                byte[] frame = binary.toByteArray();
                binary.reset();
                try {
                    handler.handle(SpeechMessage.parse(ByteBuffer.wrap(frame)));
                } catch (IllegalArgumentException e) {
                    LOG.log(System.Logger.Level.WARNING, "dropping malformed binary frame: {0}", e.getMessage());
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            handler.fail(ProtocolError.fromCloseCode(statusCode, reason)); // no-op if the turn already completed
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            handler.fail(new ProtocolError(ProtocolError.Kind.CONNECTION, "WebSocket error: " + error, -1, error));
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** A GUID without dashes: 32 lowercase hex characters. */
    static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
