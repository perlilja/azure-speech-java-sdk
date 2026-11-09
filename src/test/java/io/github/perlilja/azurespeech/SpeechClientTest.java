package io.github.perlilja.azurespeech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SpeechClientTest {

    @Test
    void closeFailsSessionsInFlight() throws Exception {
        // A server that accepts TCP but never answers the upgrade keeps the session pending.
        try (ServerSocket server = new ServerSocket(0)) {
            SpeechClientConfig config = SpeechClientConfig.builder()
                    .endpointOverride("ws://localhost:" + server.getLocalPort())
                    .subscriptionKey("k").language("en-US")
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            SpeechClient client = new SpeechClient(config);
            CompletableFuture<Void> f = client.transcribe(new ByteArrayInputStream(new byte[0]),
                    AudioFormat.PCM_16K_MONO, new TranscriptionListener() { });
            client.close();
            ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
            ProtocolError err = assertInstanceOf(ProtocolError.class, e.getCause());
            assertEquals(ProtocolError.Kind.CONNECTION, err.kind());
        }
    }

    @Test
    void transcribeAfterCloseFails() {
        SpeechClient client = new SpeechClient(SpeechClientConfig.builder()
                .region("swedencentral").subscriptionKey("k").language("en-US").build());
        client.close();
        CompletableFuture<Void> f = client.transcribe(new ByteArrayInputStream(new byte[0]),
                AudioFormat.PCM_16K_MONO, new TranscriptionListener() { });
        assertThrows(ExecutionException.class, f::get);
    }

    @Test
    void rejectsRegionThatWouldChangeTheHost() {
        assertThrows(IllegalArgumentException.class, () -> SpeechClientConfig.builder().region("evil.com/x"));
        assertThrows(IllegalArgumentException.class, () -> SpeechClient.fetchToken("evil.com#", "k"));
    }

    @Test
    void defaultSystemVersionComesFromTheBuild() {
        assertFalse(SpeechClientConfig.LIBRARY_VERSION.isBlank());
        assertFalse(SpeechClientConfig.LIBRARY_VERSION.startsWith("$"));
        assertEquals("0.1.0-SNAPSHOT", SpeechClientConfig.LIBRARY_VERSION);
    }
}
