package dev.deskcam;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * A minimal USTAR writer.
 *
 * A burst is many files in one response. Tar is the simplest container that every
 * workstation already reads, and it needs no library on the device.
 */
public class Tar {

    private static final int BLOCK = 512;
    /** A USTAR name field is 100 bytes, and the last must be the terminator. */
    private static final int MAX_NAME = 99;
    /** The size field is 11 octal digits, so 8 GiB minus one byte. */
    private static final long MAX_SIZE = (1L << 33) - 1;
    private final ByteArrayOutputStream out;

    public Tar(int expectedBytes) {
        out = new ByteArrayOutputStream(expectedBytes);
    }

    public void add(String name, byte[] data) throws IOException {
        out.write(header(name, data.length));
        out.write(data);
        int pad = (BLOCK - (data.length % BLOCK)) % BLOCK;
        out.write(new byte[pad]);
    }

    /** Two empty blocks mark the end of the archive. */
    public byte[] finish() throws IOException {
        out.write(new byte[BLOCK * 2]);
        return out.toByteArray();
    }

    /**
     * The exact byte count of an archive of these files, without building it.
     *
     * A burst used to be held three times over before the client saw any of it: the frame
     * list, then the archive, then the copy toByteArray makes. The length is arithmetic,
     * so the archive can go straight to the socket and only the frames are ever in memory.
     */
    public static long contentLength(List<byte[]> files) {
        long total = 0;
        for (byte[] f : files) {
            total += BLOCK;                                  // the header
            total += ((long) f.length + BLOCK - 1) / BLOCK * BLOCK;
        }
        return total + BLOCK * 2L;                           // the end of archive marker
    }

    /** Writes the archive straight out, in the order given. */
    public static void writeTo(OutputStream sink, List<String> names, List<byte[]> files)
            throws IOException {
        byte[] padding = new byte[BLOCK];
        for (int i = 0; i < files.size(); i++) {
            byte[] data = files.get(i);
            sink.write(header(names.get(i), data.length));
            sink.write(data);
            int pad = (BLOCK - (data.length % BLOCK)) % BLOCK;
            if (pad > 0) sink.write(padding, 0, pad);
        }
        sink.write(padding);
        sink.write(padding);
    }

    /**
     * A USTAR header, or a refusal.
     *
     * Both limits are real and both used to pass in silence: a name over 100 characters was
     * cut, and a size over 8 GB does not fit in eleven octal digits at all. Neither can
     * happen with a burst of JPEGs, which is exactly why it would go unnoticed if it ever
     * did.
     */
    private static byte[] header(String name, long size) {
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        if (nameBytes.length > MAX_NAME) {
            throw new IllegalArgumentException("the name '" + name + "' is "
                    + nameBytes.length + " bytes; a tar name field holds " + MAX_NAME);
        }
        if (name.isEmpty()) throw new IllegalArgumentException("a tar member needs a name");
        if (size < 0 || size > MAX_SIZE) {
            throw new IllegalArgumentException("a file of " + size
                    + " bytes does not fit in the 11 octal digits of a tar size field");
        }
        byte[] h = new byte[BLOCK];
        put(h, 0, 100, name);
        put(h, 100, 8, "000644 ");          // mode
        put(h, 108, 8, "000000 ");          // uid
        put(h, 116, 8, "000000 ");          // gid
        put(h, 124, 12, String.format(Locale.US, "%011o", size));
        put(h, 136, 12, String.format(Locale.US, "%011o", System.currentTimeMillis() / 1000));
        for (int i = 148; i < 156; i++) h[i] = ' ';   // checksum field counts as spaces
        h[156] = '0';                        // a normal file
        put(h, 257, 6, "ustar");
        h[263] = '0';
        h[264] = '0';

        int sum = 0;
        for (byte b : h) sum += (b & 0xff);
        put(h, 148, 7, String.format(Locale.US, "%06o", sum));
        h[154] = 0;
        h[155] = ' ';
        return h;
    }

    private static void put(byte[] h, int off, int len, String v) {
        byte[] b = v.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, off, Math.min(b.length, len - 1));
    }

    /** True when this name and size will make a header. Checked before a capture, not during. */
    public static void checkFits(String name, long size) {
        header(name, size);
    }
}
