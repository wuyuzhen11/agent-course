package com.example.agent.lesson02;

public interface ModelClient extends AutoCloseable {
    ModelResponse complete(String question);

    String buildRequestBody(String question);

    String endpoint();

    @Override
    void close();
}
