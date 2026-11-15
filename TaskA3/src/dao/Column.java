package dao;

import java.util.function.Function;

/** One field-to-column mapping: column name, how to read the value from the object, and whether it is part of the key. */
public record Column<T>(String name, Function<T, Object> getter, boolean key) {
    public static <T> Column<T> key(String name, Function<T, Object> getter) { return new Column<>(name, getter, true); }
    public static <T> Column<T> of(String name, Function<T, Object> getter) { return new Column<>(name, getter, false); }
}
