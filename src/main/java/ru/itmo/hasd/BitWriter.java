package ru.itmo.hasd;

import java.io.IOException;
import java.io.OutputStream;

final class BitWriter {
    private final OutputStream out;
    private int current;
    private int used;

    BitWriter(OutputStream out) { this.out = out; }

    void writeBit(int bit) throws IOException {
        current = (current << 1) | (bit & 1);
        if (++used == 8) {
            out.write(current);
            current = 0;
            used = 0;
        }
    }

    void writeBits(long value, int count) throws IOException {
        for (int i = count - 1; i >= 0; i--) writeBit((int) (value >>> i));
    }

    void finish() throws IOException {
        if (used != 0) {
            out.write(current << (8 - used));
            current = 0;
            used = 0;
        }
    }
}
