package com.unrealcraft.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON reader (objects -> Map, arrays -> List, numbers -> Double). Enough for glTF. */
public final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) { this.s = s; }

    public static Object parse(String text) { return new MiniJson(text).value(); }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private Object value() {
        ws();
        switch (s.charAt(i)) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': i += 4; return Boolean.TRUE;
            case 'f': i += 5; return Boolean.FALSE;
            case 'n': i += 4; return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            if (s.charAt(i++) != ':') throw new IllegalStateException("JSON: expected ':' at " + i);
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw new IllegalStateException("JSON: expected ',' or '}' at " + i);
        }
    }

    private List<Object> array() {
        i++;
        List<Object> l = new ArrayList<>();
        ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw new IllegalStateException("JSON: expected ',' or ']' at " + i);
        }
    }

    private String string() {
        if (s.charAt(i++) != '"') throw new IllegalStateException("JSON: expected string at " + i);
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: sb.append(e);
            }
        }
    }

    private Double number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw new IllegalStateException("JSON: unexpected '" + s.charAt(i) + "' at " + i);
        return Double.parseDouble(s.substring(st, i));
    }
}
