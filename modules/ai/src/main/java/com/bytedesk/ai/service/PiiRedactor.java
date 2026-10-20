package com.bytedesk.ai.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

/**
 * Masks identifiers before text is sent to an intent model. The same
 * placeholders are restored after structured extraction, so the language model
 * can preserve the slot position without receiving the original identifier.
 */
public final class PiiRedactor {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
            "\\b(?=[A-Za-z0-9_-]{6,64}\\b)(?=[A-Za-z0-9_-]*\\d)[A-Za-z0-9_-]+\\b");

    private PiiRedactor() {
    }

    public static Redaction redact(String value) {
        if (!StringUtils.hasText(value)) {
            return new Redaction(value != null ? value : "", Map.of());
        }
        Map<String, String> replacements = new LinkedHashMap<>();
        int[] counters = new int[] { 0, 0, 0 };
        String redacted = replace(value, EMAIL_PATTERN, "EMAIL", replacements, counters, 0);
        redacted = replace(redacted, PHONE_PATTERN, "PHONE", replacements, counters, 1);
        redacted = replace(redacted, IDENTIFIER_PATTERN, "ORDER", replacements, counters, 2);
        return new Redaction(redacted, Map.copyOf(replacements));
    }

    private static String replace(String value, Pattern pattern, String label,
            Map<String, String> replacements, int[] counters, int counterIndex) {
        Matcher matcher = pattern.matcher(value);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String original = matcher.group();
            String placeholder = replacementFor(original, label, replacements, counters, counterIndex);
            matcher.appendReplacement(builder, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private static String replacementFor(String original, String label,
            Map<String, String> replacements, int[] counters, int counterIndex) {
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            if (entry.getValue().equals(original)) {
                return entry.getKey();
            }
        }
        counters[counterIndex]++;
        String placeholder = "[" + label + "_" + alphaSuffix(counters[counterIndex]) + "]";
        replacements.put(placeholder, original);
        return placeholder;
    }

    private static String alphaSuffix(int index) {
        StringBuilder suffix = new StringBuilder();
        int value = index;
        while (value > 0) {
            value--;
            suffix.insert(0, (char) ('A' + value % 26));
            value /= 26;
        }
        return suffix.toString();
    }

    public record Redaction(String text, Map<String, String> replacements) {

        public Redaction {
            text = text != null ? text : "";
            replacements = replacements != null ? Map.copyOf(replacements) : Map.of();
        }

        public String restore(String value) {
            if (!StringUtils.hasText(value) || replacements.isEmpty()) {
                return value;
            }
            String restored = value;
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                restored = restored.replace(entry.getKey(), entry.getValue());
            }
            return restored;
        }
    }
}
