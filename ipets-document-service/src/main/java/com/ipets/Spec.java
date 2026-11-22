package com.ipets;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/** Declarative description of a synced collection: typed columns, validation and JSON mapping. */
final class Spec {
    enum Type { TEXT, CHOICE, DATE, DECIMAL, INT, BOOL, TIME, JSON }

    record Field(String name, String column, Type type, boolean required, int maxLength, List<String> allowed, double min, double max) {
        static Field text(String name, String column, int max, boolean required) { return new Field(name, column, Type.TEXT, required, max, List.of(), 0, 0); }
        static Field choice(String name, String column, String... allowed) { return new Field(name, column, Type.CHOICE, true, 50, List.of(allowed), 0, 0); }
        static Field date(String name, String column, boolean required) { return new Field(name, column, Type.DATE, required, 10, List.of(), 0, 0); }
        static Field decimal(String name, String column, double minExclusive, double max) { return new Field(name, column, Type.DECIMAL, true, 0, List.of(), minExclusive, max); }
        static Field integer(String name, String column, int min, int max) { return new Field(name, column, Type.INT, true, 0, List.of(), min, max); }
        static Field bool(String name, String column) { return new Field(name, column, Type.BOOL, false, 0, List.of(), 0, 0); }
        static Field time(String name, String column) { return new Field(name, column, Type.TIME, true, 5, List.of(), 0, 0); }
        static Field json(String name, String column, int maxLength) { return new Field(name, column, Type.JSON, true, maxLength, List.of(), 0, 0); }
    }

    /** {@code singleton}: one row per owner addressed as id "me" (stored with the owner's id). */
    record Collection(String name, String table, boolean petScoped, boolean singleton, List<Field> fields) {}

    private Spec() {}

    static ApiError invalid(Field f, String message) {
        return new ApiError(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_" + f.name(), message);
    }

    /** Validate a request body; returns column → JDBC value in field order. Unknown keys are ignored. */
    static Map<String, Object> validate(Collection c, Map<String, Object> body, ObjectMapper json) {
        var out = new LinkedHashMap<String, Object>();
        for (Field f : c.fields()) out.put(f.column(), value(f, body.get(f.name()), json));
        return out;
    }

    private static Object value(Field f, Object raw, ObjectMapper json) {
        switch (f.type()) {
            case TEXT -> {
                if (raw != null && !(raw instanceof String)) throw invalid(f, f.name() + " must be text.");
                String s = raw == null ? "" : (String) raw;
                if (f.required() && s.isBlank()) throw invalid(f, f.name() + " is required.");
                if (s.length() > f.maxLength()) throw invalid(f, f.name() + " is too long.");
                return s;
            }
            case CHOICE -> {
                if (!(raw instanceof String s) || !f.allowed().contains(s)) throw invalid(f, f.name() + " must be one of " + f.allowed() + ".");
                return s;
            }
            case DATE -> {
                if (raw == null || "".equals(raw)) {
                    if (f.required()) throw invalid(f, f.name() + " is required.");
                    return null;
                }
                if (!(raw instanceof String s) || !s.matches("\\d{4}-\\d{2}-\\d{2}")) throw invalid(f, f.name() + " must be YYYY-MM-DD.");
                try {
                    return Date.valueOf(LocalDate.parse(s));
                } catch (DateTimeParseException e) {
                    throw invalid(f, f.name() + " is not a real date.");
                }
            }
            case DECIMAL -> {
                if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() <= f.min() || n.doubleValue() > f.max())
                    throw invalid(f, f.name() + " must be a number above " + f.min() + " and at most " + f.max() + ".");
                return BigDecimal.valueOf(n.doubleValue());
            }
            case INT -> {
                if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < f.min() || n.doubleValue() > f.max())
                    throw invalid(f, f.name() + " must be a whole number from " + (long) f.min() + " to " + (long) f.max() + ".");
                return n.intValue();
            }
            case BOOL -> {
                if (raw != null && !(raw instanceof Boolean)) throw invalid(f, f.name() + " must be true or false.");
                return Boolean.TRUE.equals(raw);
            }
            case TIME -> {
                if (!(raw instanceof String s) || !s.matches("([01]\\d|2[0-3]):[0-5]\\d")) throw invalid(f, f.name() + " must be HH:MM.");
                return s;
            }
            case JSON -> {
                if (!(raw instanceof List) && !(raw instanceof Map)) throw invalid(f, f.name() + " must be a list or object.");
                String s = json.writeValueAsString(raw);
                if (s.length() > f.maxLength()) throw invalid(f, f.name() + " is too large.");
                return s;
            }
        }
        throw new IllegalStateException();
    }

    /** Database row → API JSON. */
    static Map<String, Object> toJson(Collection c, Map<String, Object> row, ObjectMapper json) {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", c.singleton() ? "me" : row.get("id"));
        if (c.petScoped()) out.put("petId", row.get("pet_id"));
        for (Field f : c.fields()) {
            Object v = row.get(f.column());
            out.put(f.name(), switch (f.type()) {
                case DATE -> v == null ? "" : (v instanceof Date d ? d.toLocalDate().toString() : v.toString());
                case DECIMAL -> v == null ? null : ((Number) v).doubleValue();
                case INT -> v == null ? null : ((Number) v).intValue();
                case BOOL -> v instanceof Boolean b ? b : (v instanceof Number n && n.intValue() != 0);
                case JSON -> v == null ? null : json.readValue(v.toString(), Object.class);
                default -> v == null ? "" : v;
            });
        }
        Object updated = row.get("updated_at");
        out.put("updatedAt", updated instanceof Timestamp t ? t.toInstant().toString() : String.valueOf(updated));
        out.put("deleted", row.get("deleted_at") != null);
        return out;
    }
}
