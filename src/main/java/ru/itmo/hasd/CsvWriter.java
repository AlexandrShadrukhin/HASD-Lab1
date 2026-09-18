package ru.itmo.hasd;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.util.List;

final class CsvWriter implements Closeable {
    private final Writer writer;
    private final char delimiter;
    private final String lineEnding;

    CsvWriter(Writer writer, char delimiter, String lineEnding) {
        this.writer = writer;
        this.delimiter = delimiter;
        this.lineEnding = lineEnding;
    }

    void writeRecord(List<String> fields) throws IOException {
        for (int i = 0; i < fields.size(); i++) {
            if (i != 0) writer.write(delimiter);
            writeField(fields.get(i));
        }
        writer.write(lineEnding);
    }

    private void writeField(String value) throws IOException {
        boolean quote = value.indexOf(delimiter) >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
        if (!quote) {
            writer.write(value);
            return;
        }
        writer.write('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') writer.write('"');
            writer.write(c);
        }
        writer.write('"');
    }

    @Override
    public void close() throws IOException { writer.close(); }
}
