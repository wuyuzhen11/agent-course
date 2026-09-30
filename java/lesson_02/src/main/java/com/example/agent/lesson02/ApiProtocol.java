package com.example.agent.lesson02;

public enum ApiProtocol {
    RESPONSES("responses", "/responses"),
    CHAT_COMPLETIONS("chat-completions", "/chat/completions");

    private final String label;
    private final String path;

    ApiProtocol(String label, String path) {
        this.label = label;
        this.path = path;
    }

    public String label() {
        return label;
    }

    public String path() {
        return path;
    }
}
