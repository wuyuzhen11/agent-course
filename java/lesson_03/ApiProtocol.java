enum ApiProtocol {
    RESPONSES("responses"),
    CHAT_COMPLETIONS("chat-completions");

    private final String label;

    ApiProtocol(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
