final class ModelClients {
    static AbstractModelApiClient create(
            ClientConfig config,
            ApiProtocol protocol
    ) {
        return switch (protocol) {
            case RESPONSES -> new ResponsesApiClient(config);
            case CHAT_COMPLETIONS -> new ChatCompletionsApiClient(config);
        };
    }

    static AbstractModelApiClient create(ClientConfig config) {
        return create(config, resolveProtocol(config.model()));
    }

    static ApiProtocol resolveProtocol(String model) {
        String override = System.getenv("OPENAI_API_PROTOCOL");
        if (override != null && !override.isBlank()) {
            return switch (override.trim().toLowerCase()) {
                case "responses" -> ApiProtocol.RESPONSES;
                case "chat", "chat-completions", "chat_completions" ->
                        ApiProtocol.CHAT_COMPLETIONS;
                default -> throw new IllegalArgumentException(
                        "OPENAI_API_PROTOCOL 仅支持 responses 或 chat"
                );
            };
        }

        return model.startsWith("gpt-")
                ? ApiProtocol.RESPONSES
                : ApiProtocol.CHAT_COMPLETIONS;
    }

    private ModelClients() {
    }
}
