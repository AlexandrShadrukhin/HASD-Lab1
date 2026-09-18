package ru.itmo.hasd;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class VarInt {
    private VarInt() { }

    // Отрицательный long здесь трактуется как беззнаковая 64-битная последовательность.
    public static void writeUnsigned(OutputStream out, long value) throws IOException {
        while ((value & ~0x7fL) != 0) {
            out.write((int) (value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    public static long readUnsigned(InputStream in) throws IOException {
        long result = 0;
        for (int i = 0; i < 10; i++) {
            int b = in.read();
            if (b < 0) throw new EOFException("Unexpected end of VarInt");
            if (i == 9 && (b & 0xfe) != 0) throw new IOException("Unsigned VarInt overflows 64 bits");
            result |= (long) (b & 0x7f) << (i * 7);
            if ((b & 0x80) == 0) return result;
        }
        throw new IOException("VarInt is too long");
    }

    public static int sizeUnsigned(long value) {
        int size = 1;
        while ((value & ~0x7fL) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    // ZigZag переводит небольшие по модулю signed-значения в небольшие unsigned-значения.
    public static long zigzagEncode(long value) {
        return (value << 1) ^ (value >> 63);
    }

    public static long zigzagDecode(long value) {
        return (value >>> 1) ^ -(value & 1);
    }
}
