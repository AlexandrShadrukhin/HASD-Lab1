package ru.itmo.hasd;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class HasdWriter {
    record ColumnStat(int index, String name, Schema.Type type, boolean nullable, String codec,
                      long logicalRawBytes, long baselineBodyBytes, long encodedBodyBytes,
                      long codecMetadataBytes) { }

    record Result(long rows, long originalBytes, long encodedBytes,
                  long metadataBytes, List<ColumnStat> columns) { }

    private record EncodedColumn(Schema.Column schema, Path body, long length,
                                 IntCodec.Mode intMode, long min, StringCodec.Encoding stringEncoding,
                                 FloatCodec.Encoding floatEncoding) { }

    private HasdWriter() { }

    static Result encode(Path input, Path schemaPath, Path output) throws IOException {
        Schema schema = SchemaParser.parse(schemaPath);
        if (!Files.isRegularFile(input)) throw new IOException("Input CSV does not exist: " + input);
        Path outputAbsolute = output.toAbsolutePath();
        Path parent = outputAbsolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tempDir = Files.createTempDirectory(parent, ".hasd-encode-");
        boolean completed = false;
        try {
            Spill spill = spillCsv(input, schema, tempDir);
            List<EncodedColumn> encoded = encodeColumns(schema, spill, tempDir);
            Path assembled = tempDir.resolve("result.hasd");
            assemble(assembled, schema, spill.rows, spill.lineEnding, encoded);
            Files.move(assembled, outputAbsolute, StandardCopyOption.REPLACE_EXISTING);
            completed = true;
            long encodedBytes = Files.size(outputAbsolute);
            long bodiesBytes = encoded.stream().mapToLong(EncodedColumn::length).sum();
            List<ColumnStat> columnStats = new ArrayList<>(encoded.size());
            for (int i = 0; i < encoded.size(); i++) {
                EncodedColumn value = encoded.get(i);
                String codec = switch (value.schema.type()) {
                    case INT -> value.intMode.name();
                    case FLOAT -> value.floatEncoding.mode().name();
                    case STRING -> value.stringEncoding.mode().name();
                };
                long baseline = switch (value.schema.type()) {
                    case INT -> value.length;
                    case FLOAT -> value.floatEncoding.baselineBodyBytes();
                    case STRING -> value.stringEncoding.baselineBodyBytes();
                };
                long codecMetadata = switch (value.schema.type()) {
                    case INT -> 0;
                    case FLOAT -> value.floatEncoding.metadataBytes();
                    case STRING -> value.stringEncoding.metadataBytes();
                };
                columnStats.add(new ColumnStat(i, value.schema.name(), value.schema.type(),
                        value.schema.nullable(), codec, spill.logicalRawBytes[i], baseline, value.length,
                        codecMetadata));
            }
            return new Result(spill.rows, Files.size(input), encodedBytes,
                    encodedBytes - bodiesBytes, List.copyOf(columnStats));
        } finally {
            deleteTree(tempDir);
            if (!completed) Files.deleteIfExists(tempDir.resolve("result.hasd"));
        }
    }

    private record Spill(List<Path> rawFiles, IntCodec.Stats[] intStats, FloatCodec.Stats[] floatStats,
                         StringCodec.Stats[] stringStats, long[] logicalRawBytes, long rows,
                         String lineEnding) { }

    private static Spill spillCsv(Path input, Schema schema, Path tempDir) throws IOException {
        int count = schema.columns().size();
        List<Path> rawFiles = new ArrayList<>(count);
        OutputStream[] outputs = new OutputStream[count];
        IntCodec.Stats[] stats = new IntCodec.Stats[count];
        FloatCodec.Stats[] floatStats = new FloatCodec.Stats[count];
        StringCodec.Stats[] stringStats = new StringCodec.Stats[count];
        long[] logicalRawBytes = new long[count];
        try {
            for (int i = 0; i < count; i++) {
                Path raw = tempDir.resolve("column-" + i + ".raw");
                rawFiles.add(raw);
                outputs[i] = new BufferedOutputStream(Files.newOutputStream(raw));
                Schema.Column column = schema.columns().get(i);
                if (column.type() == Schema.Type.INT) stats[i] = new IntCodec.Stats(column.signed());
                else if (column.type() == Schema.Type.FLOAT) floatStats[i] = new FloatCodec.Stats();
                else stringStats[i] = new StringCodec.Stats();
            }
            long rows = 0;
            String lineEnding;
            try (CsvReader csv = new CsvReader(Files.newBufferedReader(input, StandardCharsets.UTF_8), schema.delimiter())) {
                if (schema.hasHeader()) {
                    List<String> header = csv.readRecord();
                    if (header == null) throw new IOException("CSV is empty but schema expects a header");
                    requireFieldCount(header, count, 1);
                    for (int i = 0; i < count; i++) {
                        if (!schema.columns().get(i).name().equals(header.get(i))) {
                            throw new IOException("Header mismatch at column " + (i + 1) + ": expected '"
                                    + schema.columns().get(i).name() + "', got '" + header.get(i) + "'");
                        }
                    }
                }
                List<String> record;
                while ((record = csv.readRecord()) != null) {
                    requireFieldCount(record, count, rows + (schema.hasHeader() ? 2 : 1));
                    for (int i = 0; i < count; i++) {
                        Schema.Column column = schema.columns().get(i);
                        String value = record.get(i);
                        if (value.isEmpty() && column.type() != Schema.Type.STRING) {
                            if (!column.nullable()) throw new IOException("Empty non-nullable value at row "
                                    + (rows + 1) + ", column " + (i + 1));
                        } else if (column.type() == Schema.Type.INT) {
                            try { stats[i].accept(value); }
                            catch (IllegalArgumentException e) { throw valueError(rows, i, e); }
                        } else if (column.type() == Schema.Type.FLOAT) {
                            try { floatStats[i].accept(value); }
                            catch (IllegalArgumentException e) { throw valueError(rows, i, e); }
                        } else {
                            stringStats[i].accept(value);
                        }
                        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                        logicalRawBytes[i] += bytes.length;
                        VarInt.writeUnsigned(outputs[i], bytes.length);
                        outputs[i].write(bytes);
                    }
                    rows++;
                }
                lineEnding = csv.lineEnding();
            }
            return new Spill(rawFiles, stats, floatStats, stringStats, logicalRawBytes, rows, lineEnding);
        } finally {
            IOException failure = null;
            for (OutputStream output : outputs) {
                if (output != null) try { output.close(); } catch (IOException e) { failure = e; }
            }
            if (failure != null) throw failure;
        }
    }

    private static IOException valueError(long row, int column, IllegalArgumentException cause) {
        return new IOException("Invalid value at data row " + (row + 1) + ", column " + (column + 1)
                + ": " + cause.getMessage(), cause);
    }

    private static void requireFieldCount(List<String> record, int expected, long row) throws IOException {
        if (record.size() != expected) throw new IOException("CSV row " + row + " has " + record.size()
                + " fields; expected " + expected);
    }

    private static List<EncodedColumn> encodeColumns(Schema schema, Spill spill, Path tempDir) throws IOException {
        List<EncodedColumn> result = new ArrayList<>();
        for (int i = 0; i < schema.columns().size(); i++) {
            Schema.Column column = schema.columns().get(i);
            Path raw = spill.rawFiles.get(i);
            Path body = tempDir.resolve("column-" + i + ".body");
            IntCodec.Mode mode = null;
            long min = 0;
            StringCodec.Encoding stringEncoding = null;
            FloatCodec.Encoding floatEncoding = null;
            switch (column.type()) {
                case INT -> {
                    IntCodec.Stats stats = spill.intStats[i];
                    mode = stats.bestMode();
                    min = stats.min;
                    IntCodec.encode(raw, body, spill.rows, column, stats, mode);
                }
                case FLOAT -> floatEncoding = FloatCodec.encodeBest(raw, body, spill.rows,
                        column.nullable(), spill.floatStats[i]);
                case STRING -> stringEncoding = StringCodec.encodeBest(raw, body, spill.rows,
                        spill.stringStats[i]);
            }
            Files.deleteIfExists(raw);
            result.add(new EncodedColumn(column, body, Files.size(body), mode, min,
                    stringEncoding, floatEncoding));
        }
        return result;
    }

    private static void assemble(Path file, Schema schema, long rows, String lineEnding,
                                 List<EncodedColumn> columns) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
            out.write(HasdFormat.MAGIC);
            out.write(HasdFormat.VERSION);
            out.write(lineEnding.equals("\r\n") ? 1 : 0);
            out.write(schema.hasHeader() ? 1 : 0);
            HasdFormat.writeString(out, Character.toString(schema.delimiter()));
            VarInt.writeUnsigned(out, columns.size());
            VarInt.writeUnsigned(out, rows);
            for (EncodedColumn encoded : columns) {
                Schema.Column column = encoded.schema;
                HasdFormat.writeString(out, column.name());
                out.write(column.type().ordinal());
                out.write(column.nullable() ? 1 : 0);
                VarInt.writeUnsigned(out, encoded.length);
                if (column.type() == Schema.Type.INT) {
                    out.write(column.signed() ? 1 : 0);
                    out.write(encoded.intMode.ordinal());
                    if (encoded.intMode == IntCodec.Mode.FOR_DELTA) HasdFormat.writeLongLE(out, encoded.min);
                } else if (column.type() == Schema.Type.FLOAT) {
                    FloatCodec.Encoding encoding = encoded.floatEncoding;
                    out.write(encoding.mode().ordinal());
                    if (encoding.mode() == FloatCodec.Mode.SCALED_INT) {
                        VarInt.writeUnsigned(out, VarInt.zigzagEncode(encoding.scale()));
                        out.write(encoding.intMode().ordinal());
                        if (encoding.intMode() == IntCodec.Mode.FOR_DELTA) {
                            HasdFormat.writeLongLE(out, encoding.min());
                        }
                    }
                } else {
                    StringCodec.Encoding encoding = encoded.stringEncoding;
                    out.write(encoding.mode().ordinal());
                    StringCodec.writeMetadata(out, encoding);
                }
            }
            for (EncodedColumn encoded : columns) Files.copy(encoded.body, out);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
