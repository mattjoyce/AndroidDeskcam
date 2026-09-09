package dev.deskcam;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A minimal USTAR writer.
 *
 * A burst is many files in one response. Tar is the simplest container that every
 * workstation already reads, and it needs no library on the device.
 */
public class Tar {

    private static final int BLOCK = 512;
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

    private static byte[] header(String name, int size) {
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
}
