# azure-speech-java-sdk

[![CI](https://github.com/perlilja/azure-speech-java-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/perlilja/azure-speech-java-sdk/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Java 25+](https://img.shields.io/badge/java-25%2B-orange)

An independent, pure-Java client for the **Azure AI Speech real-time transcription** WebSocket protocol. It has no native library, so it runs where Microsoft's Java Speech SDK does not, for example on musl/Alpine images.

> **Unofficial.** This project is not affiliated with or endorsed by Microsoft. The protocol is not publicly documented by Microsoft. This library is written from a specification derived from the MIT-licensed [JavaScript Speech SDK](https://github.com/microsoft/cognitive-services-speech-sdk-js) and checked against a live Speech resource. Microsoft may change the endpoint without notice.

## Features

- Streams PCM audio and receives hypotheses and final phrases over one WebSocket (`java.net.http.WebSocket`, no extra transport dependency).
- Anonymous **speaker diarization** (`Guest-1`, `Guest-2`, ...), **language identification**, simple or detailed output with word timings.
- Subscription-key or bearer-token authentication.
- Failures map to one `ProtocolError` with a `kind` (`AUTH`, `BAD_REQUEST`, `FORBIDDEN`, `THROTTLED`, `SERVICE`, `CONNECTION`, `TIMEOUT`), so your retry policy decides what to do. The library never retries a session.
- Logging through `System.Logger`. Audio, text, keys and tokens are never logged.

Requires Java 25 or newer. The only runtime dependency is Jackson.

## Usage

```java
SpeechClientConfig config = SpeechClientConfig.builder()
        .region("swedencentral")
        .subscriptionKey(System.getenv("AZURE_SPEECH_KEY"))
        .language("sv-SE")
        .diarization(true)
        .build();

try (SpeechClient client = new SpeechClient(config)) {
    client.transcribeWav(Path.of("call.wav"), new TranscriptionListener() {
        @Override public void onPhrase(Phrase p) {
            if (p.isRecognized()) {
                System.out.printf("[%s] %s%n", p.speakerId(), p.displayText());
            }
        }
        @Override public void onError(ProtocolError e) {
            System.err.println(e.kind() + ": " + e.getMessage());
        }
    }).get();
}
```

`transcribe(InputStream pcm, AudioFormat format, listener)` takes raw PCM from any source. 16 kHz, 16-bit, mono is the verified format. Callbacks run on the WebSocket receive thread, so don't block in them.

Offsets and durations on `Phrase` are 100-nanosecond ticks (`offsetMs()` and `durationMs()` convert).

### Language identification

```java
SpeechClientConfig.builder()
        .region("uaenorth").subscriptionKey(key)
        .languageIdCandidates(List.of("sv-SE", "en-US", "ar-AE"))
        .build();   // phrases then carry primaryLanguage()
```

### Bearer tokens

```java
String token = SpeechClient.fetchToken("swedencentral", key);   // valid ~10 minutes
SpeechClientConfig.builder().region("swedencentral").authorizationToken(token)...
```

## Status

| Area | State |
|---|---|
| Framing, WAV header, context/config JSON | unit-tested against the byte layouts in the specification |
| Result parsing and session state machine | unit-tested by replaying recorded server messages |
| Live session against Azure | `LiveTranscriptionIT` (see below); run it before relying on a release |
| Translation, TTS, custom endpoints (`cid`), phrase lists, multichannel, reconnect | not implemented |

Run the live test with your own resource:

```sh
AZURE_SPEECH_KEY=... AZURE_SPEECH_REGION=swedencentral AZURE_SPEECH_TEST_WAV=/path/16k-mono.wav \
  mvn test -Dtest=LiveTranscriptionIT
```

## Building

```sh
mvn verify
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md) and the [CHANGELOG](CHANGELOG.md).

## License

[MIT](LICENSE)
