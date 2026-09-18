package ru.itmo.hasd;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public final class Main {
    private static final String USAGE = """
            Usage:
              encode --input <file.csv> --schema <schema.json> --output <file.hasd>
              decode --input <file.hasd> --output <file.csv>
              verify --original <file.csv> --restored <file.csv>
            """;

    private Main() { }

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                runAutomatically();
                return;
            }
            Map<String, String> options = parseOptions(args);
            switch (args[0]) {
                case "encode" -> encode(options);
                case "decode" -> decode(options);
                case "verify" -> verify(options);
                default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.err.print(USAGE);
            System.exit(1);
        }
    }

    private static void runAutomatically() throws Exception {
        Path root = findProjectRoot();
        Path schema = root.resolve("schema.json");
        Path encoded = root.resolve("data/loan.hasd");
        Path decoded = root.resolve("data/loan.decoded.csv");

        System.out.println("========================================");
        System.out.println("HASD Lab 1");
        System.out.println("========================================");
        System.out.println();
        System.out.println("[1/4] Dataset");
        Path dataset = DatasetDownloader.ensureDataset(root);
        if (!Files.isRegularFile(schema)) throw new IllegalStateException("schema.json not found in " + root);

        Files.deleteIfExists(encoded);
        Files.deleteIfExists(decoded);

        System.out.println();
        System.out.println("[2/4] Encoding");
        long encodeStarted = System.nanoTime();
        HasdWriter.Result encodedResult = HasdWriter.encode(dataset, schema, encoded);
        long encodeMs = elapsedMillis(encodeStarted);
        double ratio = (double) encodedResult.originalBytes() / encodedResult.encodedBytes();
        double saved = 100.0 * (encodedResult.originalBytes() - encodedResult.encodedBytes())
                / encodedResult.originalBytes();
        System.out.println("Rows: " + encodedResult.rows());
        System.out.println("Original size: " + humanSize(encodedResult.originalBytes()));
        System.out.println("Encoded size: " + humanSize(encodedResult.encodedBytes()));
        System.out.printf(Locale.ROOT, "Compression ratio: %.4fx%n", ratio);
        System.out.printf(Locale.ROOT, "Saved: %.2f%%%n", saved);
        System.out.println("Time: " + formatTime(encodeMs));
        System.out.printf(Locale.ROOT, "Speed: %.2f MB/s%n", speed(encodedResult.originalBytes(), encodeMs));
        printEncodingDiagnostics(encodedResult);

        System.out.println();
        System.out.println("[3/4] Decoding");
        long decodeStarted = System.nanoTime();
        HasdReader.Result decodedResult = HasdReader.decode(encoded, decoded);
        long decodeMs = elapsedMillis(decodeStarted);
        System.out.println("Decoded size: " + humanSize(decodedResult.decodedBytes()));
        System.out.println("Time: " + formatTime(decodeMs));
        System.out.printf(Locale.ROOT, "Speed: %.2f MB/s%n", speed(decodedResult.decodedBytes(), decodeMs));

        System.out.println();
        System.out.println("[4/4] Verification");
        Verifier.Result verification = Verifier.verify(dataset, decoded);
        System.out.println(verification.message());
        if (!verification.matches()) throw new IllegalStateException(verification.message());

        System.out.println();
        System.out.println("========================================");
        System.out.println("RESULT");
        System.out.println("Original: " + humanSize(encodedResult.originalBytes()));
        System.out.println("Encoded: " + humanSize(encodedResult.encodedBytes()));
        System.out.printf(Locale.ROOT, "Compression ratio: %.4fx%n", ratio);
        System.out.printf(Locale.ROOT, "Saved: %.2f%%%n", saved);
        System.out.println("Encode time: " + formatTime(encodeMs));
        System.out.println("Decode time: " + formatTime(decodeMs));
        System.out.println("Verification: OK");
        System.out.println("========================================");
    }

    private static Path findProjectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("build.gradle"))
                    && Files.isDirectory(candidate.resolve("src/main/java/ru/itmo/hasd"))) return candidate;
        }
        throw new IllegalStateException("Cannot find project root from " + current);
    }

    private static String humanSize(long bytes) {
        return String.format(Locale.ROOT, "%.2f GB (%d bytes)", bytes / 1_073_741_824.0, bytes);
    }

    private static String formatTime(long milliseconds) {
        long seconds = milliseconds / 1000;
        return String.format(Locale.ROOT, "%d:%02d.%03d", seconds / 60, seconds % 60, milliseconds % 1000);
    }

    private static void encode(Map<String, String> options) throws Exception {
        requireOnly(options, "input", "schema", "output");
        Path input = Path.of(required(options, "input"));
        Path schema = Path.of(required(options, "schema"));
        Path output = Path.of(required(options, "output"));
        long started = System.nanoTime();
        HasdWriter.Result result = HasdWriter.encode(input, schema, output);
        long elapsedMs = elapsedMillis(started);
        double ratio = result.encodedBytes() == 0 ? 0 : (double) result.originalBytes() / result.encodedBytes();
        double saved = result.originalBytes() == 0 ? 0
                : 100.0 * (result.originalBytes() - result.encodedBytes()) / result.originalBytes();
        System.out.println("Encoded rows: " + result.rows());
        System.out.println("Original size bytes: " + result.originalBytes());
        System.out.println("Encoded size bytes: " + result.encodedBytes());
        System.out.printf(Locale.ROOT, "Compression ratio: %.4f%n", ratio);
        System.out.printf(Locale.ROOT, "Saved percentage: %.2f%%%n", saved);
        System.out.println("Elapsed time ms: " + elapsedMs);
        System.out.printf(Locale.ROOT, "Speed MB/s: %.2f%n", speed(result.originalBytes(), elapsedMs));
        printEncodingDiagnostics(result);
    }

    private static void decode(Map<String, String> options) throws Exception {
        requireOnly(options, "input", "output");
        Path input = Path.of(required(options, "input"));
        Path output = Path.of(required(options, "output"));
        long started = System.nanoTime();
        HasdReader.Result result = HasdReader.decode(input, output);
        long elapsedMs = elapsedMillis(started);
        System.out.println("Output path: " + output.toAbsolutePath());
        System.out.println("Decoded rows: " + result.rows());
        System.out.println("Decoded file size bytes: " + result.decodedBytes());
        System.out.println("Elapsed time ms: " + elapsedMs);
        System.out.printf(Locale.ROOT, "Speed MB/s: %.2f%n", speed(result.decodedBytes(), elapsedMs));
    }

    private static void verify(Map<String, String> options) throws Exception {
        requireOnly(options, "original", "restored");
        Verifier.Result result = Verifier.verify(Path.of(required(options, "original")),
                Path.of(required(options, "restored")));
        System.out.println(result.message());
        if (!result.matches()) System.exit(2);
    }

    private static Map<String, String> parseOptions(String[] args) {
        if ((args.length - 1) % 2 != 0) throw new IllegalArgumentException("Every option needs a value");
        Map<String, String> result = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            String option = args[i];
            if (!option.startsWith("--") || option.length() == 2) {
                throw new IllegalArgumentException("Invalid option: " + option);
            }
            String key = option.substring(2);
            if (result.putIfAbsent(key, args[i + 1]) != null) {
                throw new IllegalArgumentException("Duplicate option: " + option);
            }
        }
        return result;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + key);
        return value;
    }

    private static void requireOnly(Map<String, String> options, String... allowed) {
        outer: for (String key : options.keySet()) {
            for (String candidate : allowed) if (candidate.equals(key)) continue outer;
            throw new IllegalArgumentException("Unknown option: --" + key);
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(1, (System.nanoTime() - started) / 1_000_000);
    }

    private static double speed(long bytes, long elapsedMs) {
        return bytes / 1_048_576.0 / (elapsedMs / 1000.0);
    }

    private static void printEncodingDiagnostics(HasdWriter.Result result) {
        Map<Schema.Type, Long> counts = new EnumMap<>(Schema.Type.class);
        Map<Schema.Type, Long> nullable = new EnumMap<>(Schema.Type.class);
        Map<Schema.Type, Long> encodedByType = new EnumMap<>(Schema.Type.class);
        for (Schema.Type type : Schema.Type.values()) {
            counts.put(type, 0L);
            nullable.put(type, 0L);
            encodedByType.put(type, 0L);
        }
        for (HasdWriter.ColumnStat column : result.columns()) {
            counts.merge(column.type(), 1L, Long::sum);
            if (column.nullable()) nullable.merge(column.type(), 1L, Long::sum);
            encodedByType.merge(column.type(), column.encodedBodyBytes(), Long::sum);
        }

        System.out.println();
        System.out.println("Schema:");
        for (Schema.Type type : Schema.Type.values()) {
            System.out.printf(Locale.ROOT, "  %s: %d (nullable: %d)%n",
                    type, counts.get(type), nullable.get(type));
        }
        System.out.println("Encoded bytes by type:");
        for (Schema.Type type : Schema.Type.values()) {
            System.out.printf(Locale.ROOT, "  %s: %d%n", type, encodedByType.get(type));
        }
        System.out.println("  Metadata: " + result.metadataBytes());

        printCodecSummary("String codecs:", result, Schema.Type.STRING,
                "UTF8_LENGTH", "DICTIONARY", "COMMON_PREFIX");
        printCodecSummary("Float codecs:", result, Schema.Type.FLOAT,
                "GORILLA", "SCALED_INT");

        System.out.println("Largest columns:");
        result.columns().stream()
                .sorted(Comparator.comparingLong(HasdWriter.ColumnStat::encodedBodyBytes).reversed())
                .limit(20)
                .forEach(column -> System.out.printf(Locale.ROOT,
                        "  [%d] %s | %s/%s | raw=%d B | body=%d B | %.2f%%%n",
                        column.index(), column.name(), column.type(), column.codec(), column.logicalRawBytes(),
                        column.encodedBodyBytes(), 100.0 * column.encodedBodyBytes() / result.encodedBytes()));

        System.out.println("Largest codec gains:");
        result.columns().stream()
                .filter(column -> column.baselineBodyBytes()
                        > column.encodedBodyBytes() + column.codecMetadataBytes())
                .sorted(Comparator.comparingLong((HasdWriter.ColumnStat column) ->
                        column.baselineBodyBytes() - column.encodedBodyBytes() - column.codecMetadataBytes())
                        .reversed())
                .limit(10)
                .forEach(column -> System.out.printf(Locale.ROOT,
                        "  %s | old=%d B | new=%d B | saved=%d B%n",
                        column.name(), column.baselineBodyBytes(),
                        column.encodedBodyBytes() + column.codecMetadataBytes(),
                        column.baselineBodyBytes() - column.encodedBodyBytes() - column.codecMetadataBytes()));
    }

    private static void printCodecSummary(String heading, HasdWriter.Result result, Schema.Type type,
                                          String... codecs) {
        System.out.println(heading);
        for (String codec : codecs) {
            long count = result.columns().stream()
                    .filter(column -> column.type() == type && column.codec().equals(codec)).count();
            long bytes = result.columns().stream()
                    .filter(column -> column.type() == type && column.codec().equals(codec))
                    .mapToLong(HasdWriter.ColumnStat::encodedBodyBytes).sum();
            System.out.printf(Locale.ROOT, "  %s: %d columns / %d bytes%n", codec, count, bytes);
        }
    }
}
