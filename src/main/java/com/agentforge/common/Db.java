package com.agentforge.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

/** Small JDBC helper so repositories stay readable. Column names are lower-case (DATABASE_TO_LOWER). */
@Component
public class Db {
    private final JdbcTemplate jdbc;

    public Db(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** Inserts a row and returns the generated id. */
    public long insert(String table, Map<String, Object> values) {
        List<String> cols = new ArrayList<>(values.keySet());
        String sql = "INSERT INTO " + table + " (" + String.join(",", cols) + ") VALUES ("
                + String.join(",", Collections.nCopies(cols.size(), "?")) + ")";
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < cols.size(); i++) ps.setObject(i + 1, values.get(cols.get(i)));
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? -1 : key.longValue();
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public List<Map<String, Object>> list(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        rows.forEach(Db::normalize);
        return rows;
    }

    public Optional<Map<String, Object>> one(String sql, Object... args) {
        List<Map<String, Object>> rows = list(sql, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Converts CLOBs into Strings so rows can be serialized to JSON directly. */
    private static void normalize(Map<String, Object> row) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getValue() instanceof Clob c) {
                try {
                    e.setValue(c.getSubString(1, (int) c.length()));
                } catch (Exception ex) {
                    e.setValue(null);
                }
            }
        }
    }

    public static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    public static long lng(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0 : ((Number) v).longValue();
    }

    public static double dbl(Map<String, Object> row, String key, double def) {
        Object v = row.get(key);
        return v == null ? def : ((Number) v).doubleValue();
    }

    public static boolean bool(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Boolean b && b;
    }

    @SuppressWarnings("unused")
    private static final int RETURN_KEYS = Statement.RETURN_GENERATED_KEYS;
}
