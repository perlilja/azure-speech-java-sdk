# Contributing

Thanks for helping. Bug reports, protocol findings and pull requests are welcome.

## Setup

Requires JDK 25+ and Maven 3.9+.

```sh
mvn verify          # compile, unit tests, integration tests (live test self-skips), coverage
```

The live test needs your own Speech resource; see the README. Never commit keys, tokens, audio or real transcripts.

## Pull requests

- Keep changes focused; add or update tests (protocol behavior is tested by replaying recorded messages, see `src/test/resources`).
- Public API changes need Javadoc and a CHANGELOG entry under "Unreleased".
- The library must stay dependency-light (Jackson only) and must never log audio, text, keys or tokens.
- Follow `.editorconfig`.

## Releasing (maintainer)

Tag `vX.Y.Z` on main; the Release workflow signs and publishes to Maven Central.
