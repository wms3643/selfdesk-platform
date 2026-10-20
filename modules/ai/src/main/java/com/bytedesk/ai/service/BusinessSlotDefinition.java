package com.bytedesk.ai.service;

public record BusinessSlotDefinition(
        String name,
        String description,
        boolean required,
        String pattern,
        Integer minLength,
        Integer maxLength) {

    public static BusinessSlotDefinition optional(String name, String description) {
        return new BusinessSlotDefinition(name, description, false, null, null, null);
    }

    public static BusinessSlotDefinition required(String name, String description, Integer minLength,
            Integer maxLength) {
        return new BusinessSlotDefinition(name, description, true, null, minLength, maxLength);
    }
}
