package ru.itmo.hasd;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

final class BitReader {
    private final InputStream in;
    private int current;
    private int remaining;

    BitReader(InputStream in) { this.in = in; }

    int readBit() throws IOException {
        if (remaining == 0) {
            current = in.read();
            if (current < 0) throw new EOFException("Unexpected end of bit stream");
            remaining = 8;
        }
        return (current >>> --remaining) & 1;
    }

    long readBits(int count) throws IOException {
        long value = 0;
        for (int i = 0; i < count; i++) value = (value << 1) | readBit();
        return value;
    }
}
