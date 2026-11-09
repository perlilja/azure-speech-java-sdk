package io.github.perlilja.azurespeech;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/** Immutable configuration for {@link SpeechClient}. Build with {@link #builder()}. */
public final class SpeechClientConfig {

    /** The library version from the build, sent as the default {@code system.version}. */
    static final String LIBRARY_VERSION = loadVersion();

    private static final Pattern REGION = Pattern.compile("[a-z0-9]+");

    /** The two verified paths that serve conversation transcription (spec section 3). */
    public enum Endpoint {
        CONVERSATION("/speech/recognition/conversation/cognitiveservices/v1"),
        UNIVERSAL_V2("/speech/universal/v2");

        final String path;

        Endpoint(String path) {
            this.path = path;
        }
    }

    public enum Profanity { MASKED, REMOVED, RAW, TAGGED, LABELED }

    public enum LanguageIdMode { DETECT_AT_AUDIO_START, DETECT_CONTINUOUS, DETECT_SEGMENTS }

    private final String region;
    private final String endpointOverride;
    private final String subscriptionKey;
    private final String authorizationToken;
    private final Endpoint endpoint;
    private final String language;
    private final List<String> languageIdCandidates;
    private final LanguageIdMode languageIdMode;
    private final boolean diarization;
    private final boolean diarizeIntermediates;
    private final boolean detailed;
    private final boolean wordTimings;
    private final Profanity profanity;
    private final String systemName;
    private final String systemVersion;
    private final Duration connectTimeout;
    private final Duration sessionTimeout;
    private final Duration inactivityTimeout;
    private final boolean pacing;
    private final Duration chunkDuration;

    private SpeechClientConfig(Builder b) {
        if ((b.region == null) == (b.endpointOverride == null)) {
            throw new IllegalArgumentException("set exactly one of region and endpointOverride");
        }
        if ((b.subscriptionKey == null) == (b.authorizationToken == null)) {
            throw new IllegalArgumentException("set exactly one of subscriptionKey and authorizationToken");
        }
        if (b.language == null && b.languageIdCandidates.isEmpty()) {
            throw new IllegalArgumentException("set a language or languageIdCandidates");
        }
        if (b.language != null && !b.languageIdCandidates.isEmpty()) {
            throw new IllegalArgumentException("language and languageIdCandidates are mutually exclusive");
        }
        this.region = b.region;
        this.endpointOverride = b.endpointOverride;
        this.subscriptionKey = b.subscriptionKey;
        this.authorizationToken = b.authorizationToken;
        this.endpoint = b.endpoint;
        this.language = b.language;
        this.languageIdCandidates = List.copyOf(b.languageIdCandidates);
        this.languageIdMode = b.languageIdMode;
        this.diarization = b.diarization;
        this.diarizeIntermediates = b.diarizeIntermediates;
        this.detailed = b.detailed;
        this.wordTimings = b.wordTimings;
        this.profanity = b.profanity;
        this.systemName = b.systemName;
        this.systemVersion = b.systemVersion;
        this.connectTimeout = b.connectTimeout;
        this.sessionTimeout = b.sessionTimeout;
        this.inactivityTimeout = b.inactivityTimeout;
        this.pacing = b.pacing;
        this.chunkDuration = b.chunkDuration;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- accessors ------------------------------------------------------------------------------------------

    public String subscriptionKey() { return subscriptionKey; }
    public String authorizationToken() { return authorizationToken; }
    public String language() { return language; }
    public List<String> languageIdCandidates() { return languageIdCandidates; }
    public LanguageIdMode languageIdMode() { return languageIdMode; }
    public boolean diarization() { return diarization; }
    public boolean diarizeIntermediates() { return diarizeIntermediates; }
    public boolean detailed() { return detailed; }
    public boolean wordTimings() { return wordTimings; }
    public Profanity profanity() { return profanity; }
    public String systemName() { return systemName; }
    public String systemVersion() { return systemVersion; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration sessionTimeout() { return sessionTimeout; }
    public Duration inactivityTimeout() { return inactivityTimeout; }
    public boolean pacing() { return pacing; }
    public Duration chunkDuration() { return chunkDuration; }

    boolean languageIdentification() {
        return !languageIdCandidates.isEmpty();
    }

    /** The WebSocket URL for one session; credentials travel in headers, never in the URL. */
    URI uri() {
        String base;
        if (endpointOverride != null) {
            base = endpointOverride.replaceAll("/+$", "");
        } else {
            base = "wss://" + region + ".stt.speech.microsoft" + hostSuffix(region);
        }
        String query = languageIdentification()
                ? "lidEnabled=true"
                : "language=" + URLEncoder.encode(language, StandardCharsets.UTF_8);
        return URI.create(base + endpoint.path + "?" + query);
    }

    /** Spec section 3: {@code china*} uses .azure.cn, {@code usgov*} uses .azure.us, everything else .microsoft.com. */
    static String hostSuffix(String region) {
        if (region.startsWith("china")) {
            return ".azure.cn";
        }
        if (region.startsWith("usgov")) {
            return ".azure.us";
        }
        return ".com";
    }

    /** Rejects anything that is not a plain region identifier, so it cannot alter the host name. */
    static String checkRegion(String region) {
        Objects.requireNonNull(region, "region");
        if (!REGION.matcher(region).matches()) {
            throw new IllegalArgumentException("invalid region identifier: " + region);
        }
        return region;
    }

    private static String loadVersion() {
        try (var in = SpeechClientConfig.class.getResourceAsStream("version.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version");
                if (v != null && !v.isBlank() && !v.startsWith("$")) {
                    return v;
                }
            }
        } catch (java.io.IOException ignored) {
            // fall through
        }
        return "unknown";
    }

    // ---- builder --------------------------------------------------------------------------------------------

    public static final class Builder {
        private String region;
        private String endpointOverride;
        private String subscriptionKey;
        private String authorizationToken;
        private Endpoint endpoint = Endpoint.CONVERSATION;
        private String language;
        private List<String> languageIdCandidates = List.of();
        private LanguageIdMode languageIdMode = LanguageIdMode.DETECT_AT_AUDIO_START;
        private boolean diarization;
        private boolean diarizeIntermediates;
        private boolean detailed;
        private boolean wordTimings;
        private Profanity profanity;
        private String systemName = "azure-speech-java-sdk";
        private String systemVersion = LIBRARY_VERSION;
        private Duration connectTimeout = Duration.ofSeconds(30);
        private Duration sessionTimeout = Duration.ofMinutes(30);
        private Duration inactivityTimeout = Duration.ofSeconds(120);
        private boolean pacing;
        private Duration chunkDuration = Duration.ofSeconds(1);

        /** Speech resource region identifier, e.g. {@code swedencentral}. */
        public Builder region(String region) { this.region = checkRegion(region); return this; }
        /** Full WebSocket base URL (e.g. {@code ws://localhost:8080}) replacing the regional host; for tests and proxies. */
        public Builder endpointOverride(String baseUrl) { this.endpointOverride = Objects.requireNonNull(baseUrl); return this; }
        public Builder subscriptionKey(String key) { this.subscriptionKey = Objects.requireNonNull(key); return this; }
        /** A bearer token from {@link SpeechClient#fetchToken}; valid for about ten minutes. */
        public Builder authorizationToken(String token) { this.authorizationToken = Objects.requireNonNull(token); return this; }
        public Builder endpoint(Endpoint endpoint) { this.endpoint = Objects.requireNonNull(endpoint); return this; }
        /** BCP-47 recognition locale, e.g. {@code sv-SE}. */
        public Builder language(String language) { this.language = language; return this; }
        /** Enables language identification among these locales (replaces {@link #language}). */
        public Builder languageIdCandidates(List<String> locales) { this.languageIdCandidates = List.copyOf(locales); return this; }
        public Builder languageIdMode(LanguageIdMode mode) { this.languageIdMode = Objects.requireNonNull(mode); return this; }
        /** Anonymous speaker diarization ({@code Guest-1}, {@code Guest-2}, ...). */
        public Builder diarization(boolean on) { this.diarization = on; return this; }
        public Builder diarizeIntermediates(boolean on) { this.diarizeIntermediates = on; return this; }
        /** Detailed output: phrases carry {@code NBest}. */
        public Builder detailed(boolean on) { this.detailed = on; return this; }
        /** Adds {@code WordTimings} to the detailed options (implies detailed output). */
        public Builder wordTimings(boolean on) { this.wordTimings = on; return this; }
        public Builder profanity(Profanity profanity) { this.profanity = profanity; return this; }
        public Builder systemName(String name) { this.systemName = Objects.requireNonNull(name); return this; }
        public Builder systemVersion(String version) { this.systemVersion = Objects.requireNonNull(version); return this; }
        public Builder connectTimeout(Duration d) { this.connectTimeout = Objects.requireNonNull(d); return this; }
        /** Wall-clock deadline for the whole session. */
        public Builder sessionTimeout(Duration d) { this.sessionTimeout = Objects.requireNonNull(d); return this; }
        /** Maximum silence in both directions before the session fails with TIMEOUT. */
        public Builder inactivityTimeout(Duration d) { this.inactivityTimeout = Objects.requireNonNull(d); return this; }
        /** Pace audio at twice real time after the first five seconds. Off by default; the service does not require it. */
        public Builder pacing(boolean on) { this.pacing = on; return this; }
        /** Audio duration per frame; about one second by default. */
        public Builder chunkDuration(Duration d) { this.chunkDuration = Objects.requireNonNull(d); return this; }

        public SpeechClientConfig build() {
            return new SpeechClientConfig(this);
        }
    }
}
