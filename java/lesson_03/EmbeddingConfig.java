record EmbeddingConfig(
        String baseUrl,
        String apiKey,
        String model,
        int dimensions
) {
    EmbeddingConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        if (dimensions < 1) {
            throw new IllegalArgumentException("dimensions 至少为 1");
        }

        baseUrl = removeTrailingSlash(baseUrl.strip());
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model.strip();
    }

    static EmbeddingConfig fromEnvironment() {
        String baseUrl = environment(
                "OPENAI_BASE_URL",
                "https://token.seeworld.com:8443/v1"
        );
        String apiKey = environment("OPENAI_API_KEY", "");
        String model = environment(
                "OPENAI_EMBEDDING_MODEL",
                "text-embedding-3-small"
        );
        int dimensions = parseDimensions(environment(
                "OPENAI_EMBEDDING_DIMENSIONS",
                "1536"
        ));

        return new EmbeddingConfig(
                baseUrl,
                apiKey,
                model,
                dimensions
        );
    }

    EmbeddingConfig requireApiKey() {
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "请先在当前 PowerShell 会话设置 OPENAI_API_KEY"
            );
        }
        return this;
    }

    private static int parseDimensions(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "OPENAI_EMBEDDING_DIMENSIONS 必须是正整数",
                    error
            );
        }
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank()
                ? defaultValue
                : value.strip();
    }

    private static String removeTrailingSlash(String value) {
        return value.endsWith("/")
                ? value.substring(0, value.length() - 1)
                : value;
    }
}
