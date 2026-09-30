package com.example.agent.lesson02;

public record ClientConfig(String baseUrl, String apiKey, String model) {
    public static ClientConfig fromEnvironment() {
        String baseUrl = environment("OPENAI_BASE_URL", "https://token.seeworld.com:8443/v1");
        String apiKey = environment("OPENAI_API_KEY", "");
        String model = environment("OPENAI_MODEL", "gpt-5.6-sol");
        if (baseUrl.isBlank()) {
            throw new IllegalArgumentException("OPENAI_BASE_URL 不能为空。");
        }
        if (model.isBlank()) {
            throw new IllegalArgumentException("OPENAI_MODEL 不能为空。");
        }
        return new ClientConfig(removeTrailingSlash(baseUrl), apiKey, model);
    }

    public ClientConfig requireApiKey() {
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "请先在当前 PowerShell 会话设置 OPENAI_API_KEY；不要把 Key 写入项目文件。"
            );
        }
        return this;
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String removeTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}

