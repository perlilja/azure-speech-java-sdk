package io.github.perlilja.azurespeech;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Live test against a real Speech resource. Skipped unless these are set:
 * AZURE_SPEECH_KEY, AZURE_SPEECH_REGION, AZURE_SPEECH_TEST_WAV (16 kHz 16-bit mono PCM WAV),
 * optionally AZURE_SPEECH_TEST_LOCALE (default en-US).
 */
@EnabledIfEnvironmentVariable(named = "AZURE_SPEECH_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AZURE_SPEECH_REGION", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AZURE_SPEECH_TEST_WAV", matches = ".+")
class LiveTranscriptionIT {

    @Test
    void diarizedTranscriptionOfAFile() throws Exception {
        SpeechClientConfig config = SpeechClientConfig.builder()
                .region(System.getenv("AZURE_SPEECH_REGION"))
                .subscriptionKey(System.getenv("AZURE_SPEECH_KEY"))
                .language(System.getenv().getOrDefault("AZURE_SPEECH_TEST_LOCALE", "en-US"))
                .diarization(true)
                .sessionTimeout(Duration.ofMinutes(3))
                .build();
        List<Phrase> phrases = new ArrayList<>();
        try (SpeechClient client = new SpeechClient(config)) {
            client.transcribeWav(Path.of(System.getenv("AZURE_SPEECH_TEST_WAV")), new TranscriptionListener() {
                @Override public void onPhrase(Phrase p) { phrases.add(p); }
            }).get(3, TimeUnit.MINUTES);
        }
        List<Phrase> recognized = phrases.stream().filter(Phrase::isRecognized).toList();
        assertFalse(recognized.isEmpty());
        assertTrue(recognized.stream().allMatch(p -> p.speakerId() != null && p.speakerId().startsWith("Guest-")));
        assertTrue(phrases.get(phrases.size() - 1).recognitionStatus() == RecognitionStatus.END_OF_DICTATION);
    }

    @Test
    void wrongKeyIsReportedAsAuthError() {
        SpeechClientConfig config = SpeechClientConfig.builder()
                .region(System.getenv("AZURE_SPEECH_REGION"))
                .subscriptionKey("0000deadbeef")
                .language("en-US")
                .build();
        try (SpeechClient client = new SpeechClient(config)) {
            var failure = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> client.transcribe(new java.io.ByteArrayInputStream(new byte[0]), AudioFormat.PCM_16K_MONO,
                            new TranscriptionListener() { }).get(30, TimeUnit.SECONDS));
            assertTrue(((ProtocolError) failure.getCause()).kind() == ProtocolError.Kind.AUTH);
        }
    }
}
