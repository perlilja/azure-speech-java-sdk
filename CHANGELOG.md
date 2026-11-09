# Changelog

All notable changes are documented here. Format: [Keep a Changelog](https://keepachangelog.com/), versioning: [SemVer](https://semver.org/).

## [Unreleased]

### Added
- Initial release: real-time transcription over the Speech WebSocket protocol, diarization, language identification, detailed output with word timings, key and token authentication, typed `ProtocolError`s.

### Changed
- `SpeechClient.close()` now fails sessions still in flight with `CONNECTION`, and `transcribe` after `close()` fails immediately.
- The default `system.version` comes from the build instead of a hard-coded string.
- Region identifiers are validated (`[a-z0-9]+`) in the config builder and in `fetchToken`.
- `SpeechMessage` is now package-private (protocol internal).
- Requires Java 25 (was 17).
- `WavReader` stops at the declared `data` chunk size and accepts `WAVE_FORMAT_EXTENSIBLE` PCM.
