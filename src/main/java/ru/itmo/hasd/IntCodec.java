package ru.itmo.hasd;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class IntCodec {
    enum Mode { PLAIN, DELTA, FOR_DELTA }

    static final class Stats {
        private final boolean signed;
        long plainSize;
        long deltaTailSize;
        long first;
        long min;
        long previous;
        long presentCount;

        Stats(boolean signed) { this.signed = signed; }

        void accept(String text) {
            long value;
            try {
                value = Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid INT value: " + text, e);
            }
            if (!signed && value < 0) throw new IllegalArgumentException("Negative value in unsigned INT: " + text);
            accept(value);
        }

        void accept(long value) {
            if (!signed && value < 0) throw new IllegalArgumentException("Negative value in unsigned INT: " + value);
            plainSize += VarInt.sizeUnsigned(signed ? VarInt.zigzagEncode(value) : value);
            if (presentCount == 0) {
                first = value;
                min = value;
            } else {
                deltaTailSize += VarInt.sizeUnsigned(VarInt.zigzagEncode(value - previous));
                if (value < min) min = value;
            }
            previous = value;
            presentCount++;
        }

        Mode bestMode() {
            if (presentCount == 0) return Mode.PLAIN;
            long firstPlain = VarInt.sizeUnsigned(signed ? VarInt.zigzagEncode(first) : first);
            long deltaSize = firstPlain + deltaTailSize;
            long forSize = VarInt.sizeUnsigned(first - min) + deltaTailSize;
            if (plainSize <= deltaSize && plainSize <= forSize) return Mode.PLAIN;
            return deltaSize <= forSize ? Mode.DELTA : Mode.FOR_DELTA;
        }

        long encodedSize(Mode mode) {
            if (presentCount == 0) return 0;
            long firstPlain = VarInt.sizeUnsigned(signed ? VarInt.zigzagEncode(first) : first);
            return switch (mode) {
                case PLAIN -> plainSize;
                case DELTA -> firstPlain + deltaTailSize;
                case FOR_DELTA -> VarInt.sizeUnsigned(first - min) + deltaTailSize;
            };
        }
    }

    private IntCodec() { }

    static void encode(Path raw, Path body, long rows, Schema.Column column, Stats stats, Mode mode) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(body))) {
            if (column.nullable()) writeBitmap(raw, out, rows);
            try (InputStream in = new BufferedInputStream(Files.newInputStream(raw))) {
                boolean first = true;
                long previous = 0;
                for (long row = 0; row < rows; row++) {
                    byte[] bytes = HasdFormat.readRawField(in);
                    if (bytes.length == 0 && column.nullable()) continue;
                    long value = parse(bytes);
                    long encoded;
                    if (mode == Mode.PLAIN) {
                        encoded = column.signed() ? VarInt.zigzagEncode(value) : value;
                    } else if (first) {
                        encoded = mode == Mode.FOR_DELTA ? value - stats.min
                                : (column.signed() ? VarInt.zigzagEncode(value) : value);
                    } else {
                        encoded = VarInt.zigzagEncode(value - previous);
                    }
                    VarInt.writeUnsigned(out, encoded);
                    previous = value;
                    first = false;
                }
            }
        }
    }

    private static void writeBitmap(Path raw, OutputStream out, long rows) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(raw))) {
            int bits = 0;
            int used = 0;
            for (long row = 0; row < rows; row++) {
                if (HasdFormat.readRawField(in).length != 0) bits |= 1 << used;
                if (++used == 8) {
                    out.write(bits);
                    bits = 0;
                    used = 0;
                }
            }
            if (used != 0) out.write(bits);
        }
    }

    static long parse(byte[] bytes) {
        try {
            return Long.parseLong(new String(bytes, StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid INT value", e);
        }
    }

    static final class Decoder {
        private final InputStream in;
        private final boolean signed;
        private final Mode mode;
        private final long min;
        private boolean first = true;
        private long previous;

        Decoder(InputStream in, boolean signed, Mode mode, long min) {
            this.in = in;
            this.signed = signed;
            this.mode = mode;
            this.min = min;
        }

        String next() throws IOException {
            long encoded = VarInt.readUnsigned(in);
            long value;
            if (mode == Mode.PLAIN) value = signed ? VarInt.zigzagDecode(encoded) : encoded;
            else if (first) value = mode == Mode.FOR_DELTA ? min + encoded
                    : (signed ? VarInt.zigzagDecode(encoded) : encoded);
            else value = previous + VarInt.zigzagDecode(encoded);
            previous = value;
            first = false;
            return Long.toString(value);
        }
    }
}
