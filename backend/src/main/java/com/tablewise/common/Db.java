package com.tablewise.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read helper for the SQL views: rows come back as maps with camelCase keys, ready for JSON. */
@Component
public class Db {
    private final JdbcTemplate jdbc;

    public Db(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> {
            var m = new LinkedHashMap<String, Object>();
            var md = rs.getMetaData();
            for (int c = 1; c <= md.getColumnCount(); c++) m.put(camel(md.getColumnLabel(c)), rs.getObject(c));
            return m;
        }, args);
    }

    static String camel(String s) {
        var b = new StringBuilder();
        boolean up = false;
        for (char ch : s.toCharArray()) {
            if (ch == '_') up = true;
            else { b.append(up ? Character.toUpperCase(ch) : ch); up = false; }
        }
        return b.toString();
    }
}
