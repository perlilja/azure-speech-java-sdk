package io.github.perlilja.azurespeech;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Minimal reader that locates the format and the PCM data of a RIFF/WAVE file. */
public final class WavReader {

    /** A WAV file positioned at the first PCM byte. Closing it closes the underlying stream. */
    public record Wav(AudioFormat format, InputStream pcm) implements AutoCloseable {
        @Override
        public void close() {
            try {
                pcm.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private WavReader() {
    }

    public static Wav open(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        try {
            return open(in);
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    public static Wav open(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        byte[] riff = new byte[12];
        d.readFully(riff);
        if (!"RIFF".equals(new String(riff, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                || !"WAVE".equals(new String(riff, 8, 4, java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IOException("not a RIFF/WAVE stream");
        }
        AudioFormat format = null;
        while (true) {
            byte[] chunkHead = new byte[8];
            d.readFully(chunkHead);
            String id = new String(chunkHead, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long size = ByteBuffer.wrap(chunkHead, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            if ("fmt ".equals(id)) {
                if (size < 16 || size > 1024) {
                    throw new IOException("invalid fmt chunk size " + size);
                }
                byte[] fmt = new byte[(int) size];
                d.readFully(fmt);
                ByteBuffer f = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
                int tag = f.getShort() & 0xFFFF;
                int channels = f.getShort() & 0xFFFF;
                int rate = f.getInt();
                f.getInt(); // byte rate
                f.getShort(); // block align
                int bits = f.getShort() & 0xFFFF;
                if (tag == 0xFFFE && fmt.length >= 26) { // WAVE_FORMAT_EXTENSIBLE: sub-format GUID starts with the real tag
                    tag = f.getShort(24) & 0xFFFF;
                }
                if (tag != 1) {
                    throw new IOException("only PCM WAV is supported, format tag " + tag);
                }
                format = new AudioFormat(rate, bits, channels);
                if ((size & 1) == 1) {
                    d.skipNBytes(1);
                }
            } else if ("data".equals(id)) {
                if (format == null) {
                    throw new IOException("data chunk before fmt chunk");
                }
                // 0 and 0xFFFFFFFF mean "unknown length" (streamed WAV); otherwise stop at the declared end
                // so trailing chunks (LIST, id3, ...) are not sent as audio.
                boolean bounded = size != 0 && size != 0xFFFFFFFFL;
                return new Wav(format, bounded ? new BoundedInputStream(in, size) : in);
            } else {
                d.skipNBytes(size + (size & 1));
            }
        }
    }

    /** Reads at most {@code remaining} bytes of the delegate; closing closes the delegate. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = in.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = in.read(buf, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
