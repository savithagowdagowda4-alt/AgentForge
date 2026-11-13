package com.agentforge.common;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight text-analysis utilities used by rule-based reasoning and memory retrieval. */
public final class Text {
    private static final Set<String> STOP = Set.of(
            "the", "a", "an", "and", "or", "but", "if", "then", "of", "to", "in", "on", "for", "with", "at", "by",
            "from", "is", "are", "was", "were", "be", "been", "it", "this", "that", "these", "those", "as", "we",
            "our", "you", "your", "i", "my", "me", "they", "their", "them", "he", "she", "his", "her", "not", "no",
            "do", "does", "did", "have", "has", "had", "will", "would", "should", "could", "can", "may", "might",
            "about", "into", "over", "than", "so", "such", "what", "which", "who", "how", "when", "where", "why",
            "also", "there", "here", "all", "any", "some", "more", "most", "very", "just", "need", "want", "get");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])[$€£₹]?\\d[\\d,]*(?:\\.\\d+)?\\s?(%|k|m|bn|million|billion|thousand|days?|weeks?|months?|years?|hours?)?",
            Pattern.CASE_INSENSITIVE);

    private Text() {
    }

    public static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() > 2 && !STOP.contains(w)) out.add(w);
        }
        return out;
    }

    public static Set<String> tokenSet(String s) {
        return new LinkedHashSet<>(tokens(s));
    }

    /** Overlap coefficient between two token sets, 0..1. */
    public static double similarity(String a, String b) {
        Set<String> x = tokenSet(a), y = tokenSet(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        int common = 0;
        for (String t : x) if (y.contains(t)) common++;
        return common / (double) Math.min(x.size(), y.size());
    }

    public static List<String> sentences(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split("(?<=[.!?])\\s+|\\n+")) {
            String t = p.trim();
            if (t.length() > 3) out.add(t);
        }
        return out;
    }

    /** Numeric expressions normalised (commas removed, lower-case units). */
    public static List<String> numbers(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        Matcher m = NUMBER.matcher(s);
        while (m.find()) {
            String n = m.group().replace(",", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            if (n.replaceAll("[^0-9]", "").length() > 0) out.add(n);
        }
        return out;
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public static List<String> topKeywords(String s, int n) {
        Map<String, Integer> freq = new HashMap<>();
        for (String t : tokens(s)) freq.merge(t, 1, Integer::sum);
        return freq.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue() != 0 ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()))
                .limit(n).map(Map.Entry::getKey).toList();
    }
}
