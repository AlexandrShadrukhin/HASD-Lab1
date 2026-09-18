package ru.itmo.hasd;

import java.io.Closeable;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

final class CsvReader implements Closeable {
    private final PushbackReader reader;
    private final char delimiter;
    private String lineEnding;

    CsvReader(Reader reader, char delimiter) {
        this.reader = new PushbackReader(reader, 1);
        this.delimiter = delimiter;
    }

    // Автомат разбирает CSV-записи, которые могут занимать несколько физических строк.
    List<String> readRecord() throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean afterQuote = false;
        boolean anyInput = false;
        while (true) {
            int next = reader.read();
            if (next < 0) {
                if (quoted) throw new IOException("Unterminated quoted CSV field");
                if (!anyInput && fields.isEmpty() && field.isEmpty()) return null;
                fields.add(field.toString());
                return fields;
            }
            anyInput = true;
            char c = (char) next;
            if (quoted) {
                if (c == '"') {
                    int following = reader.read();
                    if (following == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        afterQuote = true;
                        if (following >= 0) reader.unread(following);
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (afterQuote && c != delimiter && c != '\r' && c != '\n') {
                throw new IOException("Unexpected character after closing CSV quote");
            }
            if (c == delimiter) {
                fields.add(field.toString());
                field.setLength(0);
                afterQuote = false;
            } else if (c == '\n') {
                rememberLineEnding("\n");
                fields.add(field.toString());
                return fields;
            } else if (c == '\r') {
                int following = reader.read();
                if (following == '\n') rememberLineEnding("\r\n");
                else {
                    rememberLineEnding("\n");
                    if (following >= 0) reader.unread(following);
                }
                fields.add(field.toString());
                return fields;
            } else if (c == '"' && field.isEmpty() && !afterQuote) {
                quoted = true;
            } else {
                if (c == '"') throw new IOException("Quote inside unquoted CSV field");
                field.append(c);
            }
        }
    }

    String lineEnding() { return lineEnding == null ? "\n" : lineEnding; }

    private void rememberLineEnding(String ending) throws IOException {
        if (lineEnding == null) lineEnding = ending;
        else if (!lineEnding.equals(ending)) throw new IOException("Mixed CSV line endings");
    }

    @Override
    public void close() throws IOException { reader.close(); }
}
