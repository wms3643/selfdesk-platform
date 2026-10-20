package com.bytedesk.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PiiRedactorTest {

    @Test
    void masksAndRestoresPhoneEmailAndOrderIdentifiers() {
        String query = "订单A123456，手机13800138000，邮箱user@example.com，请尽快处理";

        PiiRedactor.Redaction redaction = PiiRedactor.redact(query);

        assertFalse(redaction.text().contains("A123456"));
        assertFalse(redaction.text().contains("13800138000"));
        assertFalse(redaction.text().contains("user@example.com"));
        assertTrue(redaction.text().contains("[ORDER_A]"));
        assertTrue(redaction.text().contains("[PHONE_A]"));
        assertTrue(redaction.text().contains("[EMAIL_A]"));
        assertEquals(query, redaction.restore(redaction.text()));
    }

    @Test
    void reusesTheSamePlaceholderForRepeatedIdentifiers() {
        PiiRedactor.Redaction redaction = PiiRedactor.redact(
                "手机13800138000，再次确认13800138000，订单A123456");

        assertEquals(2, occurrences(redaction.text(), "[PHONE_A]"));
        assertFalse(redaction.text().contains("[PHONE_B]"));
        assertTrue(redaction.restore("[ORDER_A]").contains("A123456"));
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
