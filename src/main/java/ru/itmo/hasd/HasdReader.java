package ru.itmo.hasd;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

final class HasdReader {
    record Result(long rows, long decodedBytes) { }

    private record ColumnMeta(String name, Schema.Type type, boolean nullable, long length,
                              boolean signed, IntCodec.Mode intMode, long min,
                              FloatCodec.Mode floatMode, int scale, StringCodec.Mode stringMode,
                              List<String> dictionary, String prefix, long offset) { }

    private record Header(char delimiter, boolean hasHeader, String lineEnding, long rows,
                          List<ColumnMeta> columns) { }

    private HasdReader() { }

    static Result decode(Path input, Path output) throws IOException {
        if (!Files.isRegularFile(input)) throw new IOException("HASD input does not exist: " + input);
        Header header = readHeader(input);
        Path absolute = output.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".hasd-decode-", ".csv");
        List<ColumnDecoder> decoders = new ArrayList<>();
        boolean completed = false;
        try {
            for (ColumnMeta meta : header.columns) decoders.add(openDecoder(input, meta, header.rows));
            try (CsvWriter csv = new CsvWriter(new OutputStreamWriter(Files.newOutputStream(temporary),
                    StandardCharsets.UTF_8), header.delimiter, header.lineEnding)) {
                if (header.hasHeader) csv.writeRecord(header.columns.stream().map(ColumnMeta::name).toList());
                List<String> row = new ArrayList<>(decoders.size());
                for (long rowIndex = 0; rowIndex < header.rows; rowIndex++) {
                    row.clear();
                    for (ColumnDecoder decoder : decoders) row.add(decoder.next(rowIndex));
                    csv.writeRecord(row);
                }
            }
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            completed = true;
            return new Result(header.rows, Files.size(absolute));
        } finally {
            IOException closeFailure = null;
            for (ColumnDecoder decoder : decoders) {
                try { decoder.close(); } catch (IOException e) { closeFailure = e; }
            }
            if (!completed) Files.deleteIfExists(temporary);
            if (closeFailure != null && completed) throw closeFailure;
        }
    }

    private static Header readHeader(Path input) throws IOException {
        long fileSize = Files.size(input);
        try (CountingInputStream in = new CountingInputStream(Files.newInputStream(input))) {
            byte[] magic = in.readNBytes(4);
            if (magic.length != 4 || !java.util.Arrays.equals(magic, HasdFormat.MAGIC)) {
                throw new IOException("Invalid HASD magic");
            }
            if (requiredByte(in, "version") != HasdFormat.VERSION) throw new IOException("Unsupported HASD version");
            int endingCode = requiredByte(in, "line ending");
            if (endingCode != 0 && endingCode != 1) throw new IOException("Invalid line ending code");
            String lineEnding = endingCode == 1 ? "\r\n" : "\n";
            int headerCode = requiredByte(in, "header flag");
            if (headerCode != 0 && headerCode != 1) throw new IOException("Invalid header flag");
            String delimiterText = HasdFormat.readString(in);
            if (delimiterText.length() != 1 || delimiterText.charAt(0) == '\r' || delimiterText.charAt(0) == '\n'
                    || delimiterText.charAt(0) == '"') throw new IOException("Invalid delimiter metadata");
            long columnCountLong = VarInt.readUnsigned(in);
            long rows = VarInt.readUnsigned(in);
            if (columnCountLong <= 0 || columnCountLong > 10_000) throw new IOException("Invalid column count");
            if (rows < 0) throw new IOException("Row count is too large");
            int columnCount = (int) columnCountLong;
            List<ColumnMeta> withoutOffsets = new ArrayList<>(columnCount);
            for (int i = 0; i < columnCount; i++) {
                String name = HasdFormat.readString(in);
                if (name.isEmpty()) throw new IOException("Empty column name");
                int typeCode = requiredByte(in, "column type");
                if (typeCode >= Schema.Type.values().length) throw new IOException("Invalid column type");
                Schema.Type type = Schema.Type.values()[typeCode];
                int nullableCode = requiredByte(in, "nullable flag");
                if (nullableCode != 0 && nullableCode != 1) throw new IOException("Invalid nullable flag");
                boolean nullable = nullableCode == 1;
                if (type == Schema.Type.STRING && nullable) throw new IOException("STRING cannot be nullable");
                long length = VarInt.readUnsigned(in);
                if (length < 0) throw new IOException("Column body is too large");
                boolean signed = false;
                IntCodec.Mode intMode = null;
                long min = 0;
                FloatCodec.Mode floatMode = null;
                int scale = 0;
                StringCodec.Mode stringMode = null;
                List<String> dictionary = List.of();
                String prefix = "";
                if (type == Schema.Type.INT) {
                    int signedCode = requiredByte(in, "signed flag");
                    if (signedCode != 0 && signedCode != 1) throw new IOException("Invalid signed flag");
                    signed = signedCode == 1;
                    int modeCode = requiredByte(in, "INT mode");
                    if (modeCode >= IntCodec.Mode.values().length) throw new IOException("Invalid INT mode");
                    intMode = IntCodec.Mode.values()[modeCode];
                    if (intMode == IntCodec.Mode.FOR_DELTA) min = HasdFormat.readLongLE(in);
                } else if (type == Schema.Type.FLOAT) {
                    int modeCode = requiredByte(in, "FLOAT mode");
                    if (modeCode >= FloatCodec.Mode.values().length) throw new IOException("Invalid FLOAT mode");
                    floatMode = FloatCodec.Mode.values()[modeCode];
                    if (floatMode == FloatCodec.Mode.SCALED_INT) {
                        long encodedScale = VarInt.readUnsigned(in);
                        long decodedScale = VarInt.zigzagDecode(encodedScale);
                        if (decodedScale < 0 || decodedScale > Integer.MAX_VALUE) {
                            throw new IOException("Invalid SCALED_INT scale");
                        }
                        scale = (int) decodedScale;
                        int intModeCode = requiredByte(in, "SCALED_INT mode");
                        if (intModeCode >= IntCodec.Mode.values().length) {
                            throw new IOException("Invalid SCALED_INT integer mode");
                        }
                        intMode = IntCodec.Mode.values()[intModeCode];
                        if (intMode == IntCodec.Mode.FOR_DELTA) min = HasdFormat.readLongLE(in);
                    }
                } else {
                    int modeCode = requiredByte(in, "STRING mode");
                    if (modeCode >= StringCodec.Mode.values().length) throw new IOException("Invalid STRING mode");
                    stringMode = StringCodec.Mode.values()[modeCode];
                    if (stringMode == StringCodec.Mode.DICTIONARY) {
                        long dictionarySize = VarInt.readUnsigned(in);
                        if (dictionarySize < 1 || dictionarySize > StringCodec.DICTIONARY_LIMIT) {
                            throw new IOException("Invalid STRING dictionary size");
                        }
                        List<String> entries = new ArrayList<>((int) dictionarySize);
                        for (int entry = 0; entry < dictionarySize; entry++) entries.add(HasdFormat.readString(in));
                        dictionary = List.copyOf(entries);
                    } else if (stringMode == StringCodec.Mode.COMMON_PREFIX) {
                        prefix = HasdFormat.readString(in);
                        if (prefix.isEmpty()) throw new IOException("Empty STRING common prefix");
                    }
                }
                withoutOffsets.add(new ColumnMeta(name, type, nullable, length, signed, intMode, min,
                        floatMode, scale, stringMode, dictionary, prefix, 0));
            }
            long offset = in.count;
            List<ColumnMeta> columns = new ArrayList<>(columnCount);
            for (ColumnMeta meta : withoutOffsets) {
                columns.add(new ColumnMeta(meta.name, meta.type, meta.nullable, meta.length,
                        meta.signed, meta.intMode, meta.min, meta.floatMode, meta.scale,
                        meta.stringMode, meta.dictionary, meta.prefix, offset));
                try { offset = Math.addExact(offset, meta.length); }
                catch (ArithmeticException e) { throw new IOException("Column offsets overflow", e); }
            }
            if (offset != fileSize) throw new IOException("HASD body lengths do not match file size");
            return new Header(delimiterText.charAt(0), headerCode == 1, lineEnding, rows, columns);
        }
    }

    private static int requiredByte(InputStream in, String name) throws IOException {
        int value = in.read();
        if (value < 0) throw new EOFException("Missing " + name);
        return value;
    }

    private static ColumnDecoder openDecoder(Path file, ColumnMeta meta, long rows) throws IOException {
        long bitmapBytes = meta.nullable ? rows / 8 + (rows % 8 == 0 ? 0 : 1) : 0;
        if (bitmapBytes > meta.length) throw new IOException("Bitmap exceeds body of column " + meta.name);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        InputStream values = new BufferedInputStream(
                new ChannelInputStream(channel, meta.offset + bitmapBytes, meta.offset + meta.length), 64 * 1024);
        InputStream bitmap = meta.nullable ? new BufferedInputStream(
                new ChannelInputStream(channel, meta.offset, meta.offset + bitmapBytes), 8 * 1024) : null;
        ValueDecoder valueDecoder = switch (meta.type) {
            case INT -> new IntCodec.Decoder(values, meta.signed, meta.intMode, meta.min)::next;
            case FLOAT -> meta.floatMode == FloatCodec.Mode.GORILLA
                    ? new FloatCodec.Decoder(values)::next
                    : new FloatCodec.ScaledDecoder(values, meta.intMode, meta.min, meta.scale)::next;
            case STRING -> StringCodec.decoder(values, meta.stringMode, meta.dictionary, meta.prefix)::next;
        };
        return new ColumnDecoder(channel, valueDecoder, bitmap, meta.nullable, rows);
    }

    @FunctionalInterface
    private interface ValueDecoder { String next() throws IOException; }

    private static final class ColumnDecoder implements AutoCloseable {
        private final FileChannel channel;
        private final ValueDecoder values;
        private final InputStream bitmap;
        private final boolean nullable;
        private final long rows;
        private int cachedByte;

        ColumnDecoder(FileChannel channel, ValueDecoder values, InputStream bitmap, boolean nullable, long rows) {
            this.channel = channel;
            this.values = values;
            this.bitmap = bitmap;
            this.nullable = nullable;
            this.rows = rows;
        }

        String next(long row) throws IOException {
            if (row < 0 || row >= rows) throw new IOException("Row outside column bounds");
            if (nullable && !present(row)) return "";
            return values.next();
        }

        private boolean present(long row) throws IOException {
            if ((row & 7) == 0) {
                cachedByte = bitmap.read();
                if (cachedByte < 0) throw new EOFException("Truncated null bitmap");
            }
            return (cachedByte & (1 << (row & 7))) != 0;
        }

        @Override
        public void close() throws IOException { channel.close(); }
    }

    private static final class ChannelInputStream extends InputStream {
        private final FileChannel channel;
        private final long end;
        private long position;

        ChannelInputStream(FileChannel channel, long position, long end) {
            this.channel = channel;
            this.position = position;
            this.end = end;
        }

        @Override
        public int read() throws IOException {
            if (position >= end) return -1;
            ByteBuffer one = ByteBuffer.allocate(1);
            int read = channel.read(one, position);
            if (read != 1) return -1;
            position++;
            return one.array()[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (position >= end) return -1;
            int wanted = (int) Math.min(length, end - position);
            ByteBuffer target = ByteBuffer.wrap(bytes, offset, wanted);
            int total = 0;
            while (target.hasRemaining()) {
                int read = channel.read(target, position + total);
                if (read < 0) break;
                if (read == 0) continue;
                total += read;
            }
            position += total;
            return total == 0 ? -1 : total;
        }
    }

    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private long count;

        CountingInputStream(InputStream delegate) { this.delegate = delegate; }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) count++;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) count += read;
            return read;
        }

        @Override
        public void close() throws IOException { delegate.close(); }
    }
}
