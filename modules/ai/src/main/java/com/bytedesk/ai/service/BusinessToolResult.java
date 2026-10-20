package com.bytedesk.ai.service;

public record BusinessToolResult(boolean success, String content, String error) {

    public static BusinessToolResult success(String content) {
        return new BusinessToolResult(true, content, null);
    }

    public static BusinessToolResult failure(String error) {
        return new BusinessToolResult(false, null, error);
    }
}
