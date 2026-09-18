package ru.itmo.hasd;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class FloatCodec {
    enum Mode { GORILLA, SCALED_INT }

    static final class Stats {
        private boolean first = true;
        private long previous;
        private int previousLeading;
        private int previousTrailing;
        private boolean hasWindow;
        private long controlBits;
        private long presentCount;
        private final ScaledStats scaled = new ScaledStats();

        void accept(String text) {
            double parsed;
            try {
                parsed = Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid FLOAT value: " + text, e);
            }
            long current = Double.doubleToRawLongBits(parsed);
            if (first) {
                first = false;
            } else {
                long xor = current ^ previous;
                if (xor == 0) {
                    controlBits++;
                } else {
                    int leading = Long.numberOfLeadingZeros(xor);
                    int trailing = Long.numberOfTrailingZeros(xor);
                    if (hasWindow && leading >= previousLeading && trailing >= previousTrailing) {
                        controlBits += 2L + 64 - previousLeading - previousTrailing;
                    } else {
                        controlBits += 2L + 6 + 6 + 64 - leading - trailing;
                        previousLeading = leading;
                        previousTrailing = trailing;
                        hasWindow = true;
                    }
                }
            }
            previous = current;
            presentCount++;
            scaled.accept(text);
        }

        long gorillaBodySize(long rows, boolean nullable) {
            return bitmapBytes(rows, nullable) + (presentCount == 0 ? 0 : 8 + bytesForBits(controlBits));
        }
    }

    private static final class ScaledStats {
        private boolean valid = true;
        private Integer scale;
        private final IntCodec.Stats integers = new IntCodec.Stats(true);

        void accept(String text) {
            if (!valid) return;
            if (!isPlainDecimal(text)) {
                valid = false;
                return;
            }
            try {
                BigDecimal decimal = new BigDecimal(text);
                if (decimal.signum() == 0 && text.startsWith("-")) {
                    valid = false;
                    return;
                }
                if (scale == null) scale = decimal.scale();
                else if (scale != decimal.scale()) {
                    valid = false;
                    return;
                }
                integers.accept(decimal.unscaledValue().longValueExact());
            } catch (ArithmeticException | NumberFormatException e) {
                valid = false;
            }
        }

        boolean valid() { return valid && scale != null; }
    }

    record Encoding(Mode mode, IntCodec.Mode intMode, int scale, long min,
                    long baselineBodyBytes, long bodyBytes, long metadataBytes) { }

    private FloatCodec() { }

    static Encoding encodeBest(Path raw, Path body, long rows, boolean nullable, Stats stats) throws IOException {
        long gorillaBody = stats.gorillaBodySize(rows, nullable);
        Mode mode = Mode.GORILLA;
        IntCodec.Mode intMode = null;
        int scale = 0;
        long min = 0;
        long selectedBody = gorillaBody;
        long metadataBytes = 0;
        if (stats.scaled.valid()) {
            intMode = stats.scaled.integers.bestMode();
            long scaledBody = bitmapBytes(rows, nullable) + stats.scaled.integers.encodedSize(intMode);
            long scaledMetadata = VarInt.sizeUnsigned(VarInt.zigzagEncode(stats.scaled.scale)) + 1L
                    + (intMode == IntCodec.Mode.FOR_DELTA ? 8 : 0);
            if (scaledBody + scaledMetadata < gorillaBody) {
                mode = Mode.SCALED_INT;
                scale = stats.scaled.scale;
                min = stats.scaled.integers.min;
                selectedBody = scaledBody;
                metadataBytes = scaledMetadata;
            }
        }
        if (mode == Mode.GORILLA) encodeGorilla(raw, body, rows, nullable);
        else encodeScaled(raw, body, rows, nullable, scale, intMode, min);
        if (Files.size(body) != selectedBody) throw new IOException("FLOAT size estimate mismatch");
        return new Encoding(mode, intMode, scale, min, gorillaBody, selectedBody, metadataBytes);
    }

    private static void encodeGorilla(Path raw, Path body, long rows, boolean nullable) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(body))) {
            if (nullable) writeBitmap(raw, out, rows);
            try (InputStream in = new BufferedInputStream(Files.newInputStream(raw))) {
                boolean first = true;
                long previous = 0;
                int previousLeading = 0;
                int previousTrailing = 0;
                boolean hasWindow = false;
                BitWriter bits = null;
                for (long row = 0; row < rows; row++) {
                    byte[] bytes = HasdFormat.readRawField(in);
                    if (bytes.length == 0 && nullable) continue;
                    double parsed = Double.parseDouble(new String(bytes, StandardCharsets.UTF_8));
                    long current = Double.doubleToRawLongBits(parsed);
                    if (first) {
                        HasdFormat.writeLongLE(out, current);
                        bits = new BitWriter(out);
                        first = false;
                    } else {
                        long xor = current ^ previous;
                        if (xor == 0) {
                            bits.writeBit(0);
                        } else {
                            int leading = Long.numberOfLeadingZeros(xor);
                            int trailing = Long.numberOfTrailingZeros(xor);
                            if (hasWindow && leading >= previousLeading && trailing >= previousTrailing) {
                                bits.writeBits(2, 2); // 10: повторно используем предыдущее окно значащих битов.
                                int significant = 64 - previousLeading - previousTrailing;
                                bits.writeBits(xor >>> previousTrailing, significant);
                            } else {
                                bits.writeBits(3, 2); // 11: записываем параметры нового окна.
                                int significant = 64 - leading - trailing;
                                bits.writeBits(leading, 6);
                                bits.writeBits(significant == 64 ? 0 : significant, 6);
                                bits.writeBits(xor >>> trailing, significant);
                                previousLeading = leading;
                                previousTrailing = trailing;
                                hasWindow = true;
                            }
                        }
                    }
                    previous = current;
                }
                if (bits != null) bits.finish();
            }
        }
    }

    private static void encodeScaled(Path raw, Path body, long rows, boolean nullable, int scale,
                                     IntCodec.Mode mode, long min) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(body))) {
            if (nullable) writeBitmap(raw, out, rows);
            try (InputStream in = new BufferedInputStream(Files.newInputStream(raw))) {
                boolean first = true;
                long previous = 0;
                for (long row = 0; row < rows; row++) {
                    byte[] bytes = HasdFormat.readRawField(in);
                    if (bytes.length == 0 && nullable) continue;
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    long value;
                    try {
                        BigDecimal decimal = new BigDecimal(text);
                        if (decimal.scale() != scale) throw new NumberFormatException("scale changed");
                        value = decimal.unscaledValue().longValueExact();
                    } catch (ArithmeticException | NumberFormatException e) {
                        throw new IOException("Invalid SCALED_INT value: " + text, e);
                    }
                    long encoded;
                    if (mode == IntCodec.Mode.PLAIN) encoded = VarInt.zigzagEncode(value);
                    else if (first) encoded = mode == IntCodec.Mode.FOR_DELTA
                            ? value - min : VarInt.zigzagEncode(value);
                    else encoded = VarInt.zigzagEncode(value - previous);
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

    static final class Decoder {
        private final InputStream in;
        private BitReader bits;
        private boolean first = true;
        private long previous;
        private int previousLeading;
        private int previousTrailing;
        private boolean hasWindow;

        Decoder(InputStream in) { this.in = in; }

        String next() throws IOException {
            long current;
            if (first) {
                current = HasdFormat.readLongLE(in);
                bits = new BitReader(in);
                first = false;
            } else if (bits.readBit() == 0) {
                current = previous;
            } else {
                long xor;
                if (bits.readBit() == 0) {
                    if (!hasWindow) throw new IOException("Gorilla stream reuses a missing window");
                    int significant = 64 - previousLeading - previousTrailing;
                    xor = bits.readBits(significant) << previousTrailing;
                } else {
                    int leading = (int) bits.readBits(6);
                    int encodedSignificant = (int) bits.readBits(6);
                    int significant = encodedSignificant == 0 ? 64 : encodedSignificant;
                    int trailing = 64 - leading - significant;
                    if (trailing < 0) throw new IOException("Invalid Gorilla window");
                    xor = bits.readBits(significant) << trailing;
                    previousLeading = leading;
                    previousTrailing = trailing;
                    hasWindow = true;
                }
                current = previous ^ xor;
            }
            previous = current;
            return Double.toString(Double.longBitsToDouble(current));
        }
    }

    static final class ScaledDecoder {
        private final IntCodec.Decoder integers;
        private final int scale;

        ScaledDecoder(InputStream in, IntCodec.Mode mode, long min, int scale) {
            this.integers = new IntCodec.Decoder(in, true, mode, min);
            this.scale = scale;
        }

        String next() throws IOException {
            long value = Long.parseLong(integers.next());
            return BigDecimal.valueOf(value, scale).toPlainString();
        }
    }

    private static long bitmapBytes(long rows, boolean nullable) {
        return nullable ? rows / 8 + (rows % 8 == 0 ? 0 : 1) : 0;
    }

    private static long bytesForBits(long bits) {
        return bits / 8 + (bits % 8 == 0 ? 0 : 1);
    }

    private static boolean isPlainDecimal(String text) {
        int index = 0;
        if (text.isEmpty()) return false;
        char first = text.charAt(0);
        if (first == '+' || first == '-') index++;
        int integerStart = index;
        while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
        if (index == integerStart) return false;
        if (index == text.length()) return true;
        if (text.charAt(index++) != '.') return false;
        int fractionStart = index;
        while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
        return index == text.length() && index > fractionStart;
    }
}
