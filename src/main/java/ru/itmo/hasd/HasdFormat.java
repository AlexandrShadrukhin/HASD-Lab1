package ru.itmo.hasd;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class HasdFormat {
    static final byte[] MAGIC = {'H', 'A', 'S', 'D'};
    static final int VERSION = 2;

    private HasdFormat() { }

    static void writeString(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        VarInt.writeUnsigned(out, bytes.length);
        out.write(bytes);
    }

    static String readString(InputStream in) throws IOException {
        long size = VarInt.readUnsigned(in);
        if (size < 0 || size > Integer.MAX_VALUE) throw new IOException("String is too large");
        byte[] bytes = in.readNBytes((int) size);
        if (bytes.length != (int) size) throw new EOFException("Truncated UTF-8 string");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static void writeLongLE(OutputStream out, long value) throws IOException {
        for (int i = 0; i < 8; i++) out.write((int) (value >>> (i * 8)) & 0xff);
    }

    static long readLongLE(InputStream in) throws IOException {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            int b = in.read();
            if (b < 0) throw new EOFException("Unexpected end of 64-bit value");
            value |= (long) b << (i * 8);
        }
        return value;
    }

    static byte[] readRawField(InputStream in) throws IOException {
        long size = VarInt.readUnsigned(in);
        if (size < 0 || size > Integer.MAX_VALUE) throw new IOException("Raw field is too large");
        byte[] bytes = in.readNBytes((int) size);
        if (bytes.length != (int) size) throw new EOFException("Truncated raw column file");
        return bytes;
    }
}
