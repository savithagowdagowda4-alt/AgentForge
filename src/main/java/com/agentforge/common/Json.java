package com.agentforge.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JSON helpers, including a tolerant parser for model output that may wrap JSON in prose or fences. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private Json() {
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode arr() {
        return MAPPER.createArrayNode();
    }

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode read(String s) {
        if (s == null || s.isBlank()) return obj();
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            return obj();
        }
    }

    /** Extracts the first top-level JSON object from free text. Throws if none can be parsed. */
    public static JsonNode extractObject(String text) {
        if (text == null) throw new IllegalArgumentException("Empty model response");
        String t = text.trim();
        int start = t.indexOf('{');
        if (start < 0) throw new IllegalArgumentException("Model response contained no JSON object");
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < t.length(); i++) {
            char c = t.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    try {
                        return MAPPER.readTree(t.substring(start, i + 1));
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Model returned malformed JSON: " + e.getMessage());
                    }
                }
            }
        }
        throw new IllegalArgumentException("Model response JSON was incomplete");
    }

    public static String text(JsonNode n, String field, String def) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? def : v.asText(def);
    }

    public static double num(JsonNode n, String field, double def) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || !v.isNumber() ? def : v.asDouble();
    }
}
