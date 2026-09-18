package ru.itmo.hasd;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class Verifier {
    record Result(boolean matches, long rows, String message) { }

    private Verifier() { }

    static Result verify(Path original, Path restored) throws IOException {
        char originalDelimiter = detectDelimiter(original);
        char restoredDelimiter = detectDelimiter(restored);
        if (originalDelimiter != restoredDelimiter) {
            return new Result(false, 0, "Delimiter mismatch: original='" + originalDelimiter
                    + "', decoded='" + restoredDelimiter + "'");
        }
        try (CsvReader left = new CsvReader(Files.newBufferedReader(original, StandardCharsets.UTF_8), originalDelimiter);
             CsvReader right = new CsvReader(Files.newBufferedReader(restored, StandardCharsets.UTF_8), restoredDelimiter)) {
            long row = 0;
            while (true) {
                List<String> a = left.readRecord();
                List<String> b = right.readRecord();
                if (a == null || b == null) {
                    if (a == null && b == null) return new Result(true, row, "OK -- " + row + " rows match");
                    return new Result(false, row, "Record count mismatch after row " + row);
                }
                row++;
                if (a.size() != b.size()) return new Result(false, row, "Mismatch at row " + row
                        + ": original fields=" + a.size() + ", decoded fields=" + b.size());
                for (int column = 0; column < a.size(); column++) {
                    if (!equal(a.get(column), b.get(column))) {
                        return new Result(false, row, "Mismatch at row " + row + ", column " + (column + 1)
                                + ": original=" + printable(a.get(column)) + ", decoded=" + printable(b.get(column)));
                    }
                }
            }
        }
    }

    private static boolean equal(String left, String right) {
        if (left.equals(right)) return true;
        if (left.isEmpty() || right.isEmpty()) return false;
        try {
            double a = Double.parseDouble(left);
            double b = Double.parseDouble(right);
            if (Double.isNaN(a) || Double.isNaN(b)) return Double.isNaN(a) && Double.isNaN(b);
            long aBits = Double.doubleToRawLongBits(a);
            long bBits = Double.doubleToRawLongBits(b);
            if (Double.isInfinite(a) || Double.isInfinite(b) || (a == 0.0 && b == 0.0)) return aBits == bBits;
            try {
                return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
            } catch (NumberFormatException ignored) {
                return aBits == bBits;
            }
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String printable(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\r", "\\r")
                .replace("\n", "\\n").replace("\"", "\\\"") + '"';
    }

    private static char detectDelimiter(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            boolean quoted = false;
            int commas = 0;
            int semicolons = 0;
            int tabs = 0;
            int pipes = 0;
            for (int seen = 0; seen < 1_000_000; seen++) {
                int next = reader.read();
                if (next < 0 || (!quoted && (next == '\r' || next == '\n'))) break;
                if (next == '"') {
                    if (quoted) {
                        reader.mark(1);
                        int following = reader.read();
                        if (following != '"') {
                            quoted = false;
                            reader.reset();
                        }
                    } else quoted = true;
                } else if (!quoted) {
                    if (next == ',') commas++;
                    else if (next == ';') semicolons++;
                    else if (next == '\t') tabs++;
                    else if (next == '|') pipes++;
                }
            }
            int max = Math.max(Math.max(commas, semicolons), Math.max(tabs, pipes));
            if (semicolons == max && max > commas) return ';';
            if (tabs == max && max > commas) return '\t';
            if (pipes == max && max > commas) return '|';
            return ',';
        }
    }
}
