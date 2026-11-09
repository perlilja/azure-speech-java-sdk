package io.github.perlilja.azurespeech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientMessagesTest {

    private static SpeechClientConfig.Builder base() {
        return SpeechClientConfig.builder().region("uaenorth").subscriptionKey("k").language("en-US");
    }

    @Test
    void diarizedSimpleContextMatchesVerifiedShape() {
        JsonNode c = ClientMessages.speechContext(base().diarization(true).build(), "abc");
        assertEquals("Conversation", c.at("/phraseDetection/mode").asText());
        assertEquals("en-US", c.at("/phraseDetection/language").asText());
        assertEquals("Anonymous", c.at("/phraseDetection/speakerDiarization/mode").asText());
        assertEquals("abc", c.at("/phraseDetection/speakerDiarization/audioSessionId").asText());
        assertEquals(0, c.at("/phraseDetection/speakerDiarization/audioOffsetMs").asInt(-1));
        assertFalse(c.at("/phraseDetection/speakerDiarization/diarizeIntermediates").asBoolean(true));
        assertEquals("Simple", c.at("/phraseOutput/format").asText());
    }

    @Test
    void noDiarizationOmitsTheBlock() {
        JsonNode c = ClientMessages.speechContext(base().build(), "abc");
        assertTrue(c.at("/phraseDetection/speakerDiarization").isMissingNode());
    }

    @Test
    void detailedWithWordTimings() {
        JsonNode c = ClientMessages.speechContext(base().wordTimings(true).build(), "abc");
        assertEquals("Detailed", c.at("/phraseOutput/format").asText());
        assertEquals("WordTimings", c.at("/phraseOutput/detailed/options/0").asText());
    }

    @Test
    void languageIdentificationDropsLanguageAndAddsLanguageId() {
        SpeechClientConfig cfg = SpeechClientConfig.builder().region("uaenorth").subscriptionKey("k")
                .languageIdCandidates(List.of("sv-SE", "en-US", "ar-AE")).build();
        JsonNode c = ClientMessages.speechContext(cfg, "abc");
        assertTrue(c.at("/phraseDetection/language").isMissingNode());
        assertEquals(3, c.at("/languageId/languages").size());
        assertEquals("DetectAtAudioStart", c.at("/languageId/mode").asText());
        assertEquals("Recognize", c.at("/languageId/onSuccess/action").asText());
        assertEquals("Always", c.at("/phraseOutput/phraseResults/resultType").asText());
        assertEquals("lidEnabled=true", cfg.uri().getQuery());
    }

    @Test
    void speechConfigCarriesAudioSource() {
        JsonNode c = ClientMessages.speechConfig(base().systemName("me").systemVersion("9").build(), AudioFormat.PCM_16K_MONO);
        assertEquals("me", c.at("/context/system/name").asText());
        assertEquals(16000, c.at("/context/audio/source/samplerate").asInt());
        assertEquals(16, c.at("/context/audio/source/bitspersample").asInt());
        assertEquals(1, c.at("/context/audio/source/channelcount").asInt());
    }

    @Test
    void urlAndHostSuffixRules() {
        assertEquals("wss://uaenorth.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=en-US",
                base().build().uri().toString());
        assertEquals("wss://swedencentral.stt.speech.microsoft.com/speech/universal/v2?language=sv-SE",
                base().region("swedencentral").language("sv-SE").endpoint(SpeechClientConfig.Endpoint.UNIVERSAL_V2).build().uri().toString());
        assertEquals(".azure.cn", SpeechClientConfig.hostSuffix("chinaeast2"));
        assertEquals(".azure.us", SpeechClientConfig.hostSuffix("usgovvirginia"));
        assertEquals(".com", SpeechClientConfig.hostSuffix("westeurope"));
    }

    @Test
    void configValidation() {
        assertThrows(IllegalArgumentException.class, () -> SpeechClientConfig.builder().region("r").language("en-US").build());
        assertThrows(IllegalArgumentException.class, () -> SpeechClientConfig.builder().subscriptionKey("k").language("en-US").build());
        assertThrows(IllegalArgumentException.class, () -> SpeechClientConfig.builder().region("r").subscriptionKey("k").build());
        assertThrows(IllegalArgumentException.class, () -> base().subscriptionKey("k").authorizationToken("t").build());
        assertThrows(IllegalArgumentException.class, () -> base().languageIdCandidates(List.of("sv-SE")).build());
    }

    @Test
    void idsAre32LowercaseHex() {
        assertTrue(SpeechClient.newId().matches("[0-9a-f]{32}"));
    }
}
