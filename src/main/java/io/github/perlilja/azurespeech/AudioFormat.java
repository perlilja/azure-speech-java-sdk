package io.github.perlilja.azurespeech;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** PCM audio format. The wire format is always PCM inside a WAV container (spec 8.3). */
public record AudioFormat(int sampleRate, int bitsPerSample, int channels) {

    /** The default and the only format verified against the service: 16 kHz, 16-bit, mono. */
    public static final AudioFormat PCM_16K_MONO = new AudioFormat(16000, 16, 1);

    public AudioFormat {
        if (sampleRate <= 0 || bitsPerSample <= 0 || channels <= 0) {
            throw new IllegalArgumentException("invalid audio format");
        }
    }

    public int bytesPerSecond() {
        return sampleRate * channels * bitsPerSample / 8;
    }

    /**
     * The 44-byte WAV header sent in the first audio frame of a turn: both size fields are zero
     * because the length of the stream is not known up front.
     */
    public byte[] wavHeader() {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        b.putInt(0);
        b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16);
        b.putShort((short) 1); // PCM
        b.putShort((short) channels);
        b.putInt(sampleRate);
        b.putInt(bytesPerSecond());
        b.putShort((short) (channels * bitsPerSample / 8));
        b.putShort((short) bitsPerSample);
        b.put("data".getBytes(StandardCharsets.US_ASCII));
        b.putInt(0);
        return b.array();
    }
}
