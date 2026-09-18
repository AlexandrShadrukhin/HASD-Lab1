package ru.itmo.hasd;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StringCodec {
    static final int DICTIONARY_LIMIT = 65_536;

    enum Mode { UTF8_LENGTH, DICTIONARY, COMMON_PREFIX }

    static final class Stats {
        private Map<String, Integer> dictionary = new LinkedHashMap<>();
        private String commonPrefix;

        void accept(String value) {
            if (dictionary != null && !dictionary.containsKey(value)) {
                if (dictionary.size() == DICTIONARY_LIMIT) dictionary = null;
                else dictionary.put(value, dictionary.size());
            }
            if (!value.isEmpty()) {
                commonPrefix = commonPrefix == null ? value : commonPrefix(commonPrefix, value);
            }
        }

        List<String> dictionary() {
            return dictionary == null ? null : new ArrayList<>(dictionary.keySet());
        }

        Map<String, Integer> dictionaryIds() { return dictionary; }

        String prefix() { return commonPrefix == null ? "" : commonPrefix; }
    }

    record Encoding(Mode mode, List<String> dictionary, String prefix,
                    long baselineBodyBytes, long bodyBytes, long metadataBytes) { }

    private StringCodec() { }

    static Encoding encodeBest(Path raw, Path body, long rows, Stats stats) throws IOException {
        long plainBody = Files.size(raw);
        Mode selected = Mode.UTF8_LENGTH;
        long selectedTotal = plainBody;
        long selectedBody = plainBody;
        long selectedMetadata = 0;
        List<String> dictionary = stats.dictionary();
        String prefix = stats.prefix();

        long dictionaryBody = Long.MAX_VALUE;
        long dictionaryMetadata = Long.MAX_VALUE;
        if (dictionary != null && !dictionary.isEmpty()) {
            int bits = bitsPerId(dictionary.size());
            dictionaryBody = packedBytes(rows, bits);
            dictionaryMetadata = VarInt.sizeUnsigned(dictionary.size());
            for (String entry : dictionary) {
                byte[] bytes = entry.getBytes(StandardCharsets.UTF_8);
                dictionaryMetadata += VarInt.sizeUnsigned(bytes.length) + bytes.length;
            }
            long total = Math.addExact(dictionaryBody, dictionaryMetadata);
            if (total < selectedTotal) {
                selected = Mode.DICTIONARY;
                selectedTotal = total;
                selectedBody = dictionaryBody;
                selectedMetadata = dictionaryMetadata;
            }
        }

        Path prefixCandidate = body.resolveSibling(body.getFileName() + ".prefix");
        long prefixBody = Long.MAX_VALUE;
        long prefixMetadata = Long.MAX_VALUE;
        if (!prefix.isEmpty()) {
            encodePrefix(raw, prefixCandidate, rows, prefix);
            prefixBody = Files.size(prefixCandidate);
            byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
            prefixMetadata = VarInt.sizeUnsigned(prefixBytes.length) + prefixBytes.length;
            long total = Math.addExact(prefixBody, prefixMetadata);
            if (total < selectedTotal) {
                selected = Mode.COMMON_PREFIX;
                selectedBody = prefixBody;
                selectedMetadata = prefixMetadata;
            }
        }

        switch (selected) {
            case UTF8_LENGTH -> Files.move(raw, body, StandardCopyOption.REPLACE_EXISTING);
            case DICTIONARY -> encodeDictionary(raw, body, rows, stats.dictionaryIds(), dictionary.size());
            case COMMON_PREFIX -> Files.move(prefixCandidate, body, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(prefixCandidate);
        if (Files.size(body) != selectedBody) throw new IOException("STRING size estimate mismatch");
        return new Encoding(selected, selected == Mode.DICTIONARY ? dictionary : List.of(),
                selected == Mode.COMMON_PREFIX ? prefix : "", plainBody, selectedBody, selectedMetadata);
    }

    private static void encodeDictionary(Path raw, Path body, long rows, Map<String, Integer> ids,
                                         int dictionarySize) throws IOException {
        int bits = bitsPerId(dictionarySize);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(raw));
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(body))) {
            if (bits == 0) {
                for (long row = 0; row < rows; row++) HasdFormat.readRawField(in);
                return;
            }
            BitWriter writer = new BitWriter(out);
            for (long row = 0; row < rows; row++) {
                String value = new String(HasdFormat.readRawField(in), StandardCharsets.UTF_8);
                Integer id = ids.get(value);
                if (id == null) throw new IOException("Value missing from STRING dictionary");
                writer.writeBits(id, bits);
            }
            writer.finish();
        }
    }

    private static void encodePrefix(Path raw, Path body, long rows, String prefix) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(raw));
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(body))) {
            for (long row = 0; row < rows; row++) {
                String value = new String(HasdFormat.readRawField(in), StandardCharsets.UTF_8);
                if (value.isEmpty()) {
                    VarInt.writeUnsigned(out, 0);
                } else {
                    if (!value.startsWith(prefix)) throw new IOException("Invalid common prefix");
                    byte[] suffix = value.substring(prefix.length()).getBytes(StandardCharsets.UTF_8);
                    VarInt.writeUnsigned(out, (long) suffix.length + 1);
                    out.write(suffix);
                }
            }
        }
    }

    static void writeMetadata(OutputStream out, Encoding encoding) throws IOException {
        if (encoding.mode == Mode.DICTIONARY) {
            VarInt.writeUnsigned(out, encoding.dictionary.size());
            for (String entry : encoding.dictionary) HasdFormat.writeString(out, entry);
        } else if (encoding.mode == Mode.COMMON_PREFIX) {
            HasdFormat.writeString(out, encoding.prefix);
        }
    }

    static ValueDecoder decoder(InputStream in, Mode mode, List<String> dictionary, String prefix) {
        return switch (mode) {
            case UTF8_LENGTH -> () -> HasdFormat.readString(in);
            case DICTIONARY -> new DictionaryDecoder(in, dictionary)::next;
            case COMMON_PREFIX -> () -> {
                long marker = VarInt.readUnsigned(in);
                if (marker == 0) return "";
                long size = marker - 1;
                if (size > Integer.MAX_VALUE) throw new IOException("STRING suffix is too large");
                byte[] bytes = in.readNBytes((int) size);
                if (bytes.length != (int) size) throw new IOException("Truncated STRING suffix");
                return prefix + new String(bytes, StandardCharsets.UTF_8);
            };
        };
    }

    @FunctionalInterface
    interface ValueDecoder { String next() throws IOException; }

    private static final class DictionaryDecoder {
        private final List<String> dictionary;
        private final BitReader bits;
        private final int bitsPerId;

        DictionaryDecoder(InputStream in, List<String> dictionary) {
            this.dictionary = dictionary;
            this.bits = new BitReader(in);
            this.bitsPerId = bitsPerId(dictionary.size());
        }

        String next() throws IOException {
            int id = bitsPerId == 0 ? 0 : (int) bits.readBits(bitsPerId);
            if (id < 0 || id >= dictionary.size()) throw new IOException("Invalid STRING dictionary ID");
            return dictionary.get(id);
        }
    }

    private static int bitsPerId(int dictionarySize) {
        return dictionarySize <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(dictionarySize - 1);
    }

    private static long packedBytes(long rows, int bits) {
        if (bits == 0) return 0;
        long totalBits = Math.multiplyExact(rows, bits);
        return totalBits / 8 + (totalBits % 8 == 0 ? 0 : 1);
    }

    private static String commonPrefix(String left, String right) {
        int length = Math.min(left.length(), right.length());
        int index = 0;
        while (index < length && left.charAt(index) == right.charAt(index)) index++;
        if (index > 0 && Character.isHighSurrogate(left.charAt(index - 1))) index--;
        return left.substring(0, index);
    }
}
