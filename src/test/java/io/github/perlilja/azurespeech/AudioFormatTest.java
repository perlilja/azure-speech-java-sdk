package io.github.perlilja.azurespeech;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AudioFormatTest {

    /** The exact bytes listed in spec 8.3 for 16 kHz 16-bit mono. */
    @Test
    void wavHeaderMatchesSpecBytes() {
        byte[] expected = hex("52494646" + "00000000" + "5741564566 6D7420".replace(" ", "")
                + "10000000" + "0100" + "0100" + "803E0000" + "007D0000" + "0200" + "1000"
                + "64617461" + "00000000");
        assertEquals(44, expected.length);
        assertArrayEquals(expected, AudioFormat.PCM_16K_MONO.wavHeader());
    }

    @Test
    void bytesPerSecond() {
        assertEquals(32000, AudioFormat.PCM_16K_MONO.bytesPerSecond());
        assertEquals(16000, new AudioFormat(8000, 16, 1).bytesPerSecond());
    }

    @Test
    void wavReaderFindsFormatAndData() throws IOException {
        byte[] header = AudioFormat.PCM_16K_MONO.wavHeader();
        byte[] pcm = {1, 2, 3, 4};
        byte[] file = new byte[header.length + pcm.length];
        System.arraycopy(header, 0, file, 0, header.length);
        System.arraycopy(pcm, 0, file, header.length, pcm.length);
        try (WavReader.Wav wav = WavReader.open(new ByteArrayInputStream(file))) {
            assertEquals(AudioFormat.PCM_16K_MONO, wav.format());
            assertArrayEquals(pcm, wav.pcm().readAllBytes());
        }
    }

    @Test
    void wavReaderRejectsNonRiff() {
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> WavReader.open(new ByteArrayInputStream("not a wav file at all".getBytes(StandardCharsets.US_ASCII))));
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
