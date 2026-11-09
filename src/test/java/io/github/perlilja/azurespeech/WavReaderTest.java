package io.github.perlilja.azurespeech;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WavReaderTest {

    private static byte[] wav(int dataSize, byte[] data, byte[] trailing) {
        ByteBuffer b = ByteBuffer.allocate(44 + data.length + trailing.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(AudioFormat.PCM_16K_MONO.wavHeader());
        b.putInt(40, dataSize);
        b.position(44);
        b.put(data).put(trailing);
        return b.array();
    }

    private static byte[] readAll(WavReader.Wav w) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        w.pcm().transferTo(out);
        return out.toByteArray();
    }

    @Test
    void stopsAtDeclaredDataSize() throws Exception {
        byte[] pcm = {1, 2, 3, 4};
        byte[] list = "LIST....".getBytes(StandardCharsets.US_ASCII);
        try (WavReader.Wav w = WavReader.open(new ByteArrayInputStream(wav(4, pcm, list)))) {
            assertEquals(AudioFormat.PCM_16K_MONO, w.format());
            assertArrayEquals(pcm, readAll(w));
        }
    }

    @Test
    void zeroSizeMeansStreamedAndReadsToEnd() throws Exception {
        byte[] pcm = {1, 2, 3, 4, 5, 6};
        try (WavReader.Wav w = WavReader.open(new ByteArrayInputStream(wav(0, pcm, new byte[0])))) {
            assertArrayEquals(pcm, readAll(w));
        }
    }

    @Test
    void rejectsNonWav() {
        assertThrows(IOException.class, () -> WavReader.open(new ByteArrayInputStream(new byte[64])));
    }

    @Test
    void rejectsTruncatedFmtChunk() {
        byte[] bad = wav(0, new byte[0], new byte[0]);
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 4);
        assertThrows(IOException.class, () -> WavReader.open(new ByteArrayInputStream(bad)));
    }
}
