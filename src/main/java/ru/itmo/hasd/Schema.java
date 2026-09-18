package ru.itmo.hasd;

import java.util.List;

record Schema(char delimiter, boolean hasHeader, List<Column> columns) {
    Schema {
        columns = List.copyOf(columns);
        if (columns.isEmpty()) throw new IllegalArgumentException("Schema must contain at least one column");
    }

    enum Type { INT, FLOAT, STRING }

    record Column(String name, Type type, boolean signed, boolean nullable) {
        Column {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("Column name must not be empty");
            if (type != Type.INT && signed) throw new IllegalArgumentException("Only INT can be signed: " + name);
            if (type == Type.STRING && nullable) throw new IllegalArgumentException("STRING does not use nullable: " + name);
        }
    }
}
