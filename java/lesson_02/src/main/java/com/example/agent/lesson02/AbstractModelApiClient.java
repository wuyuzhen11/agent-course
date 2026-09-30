package com.example.agent.lesson02;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public abstract class AbstractModelApiClient implements ModelClient {
    protected final ClientConfig config;
    protected final ObjectMapper json;
    private final HttpClient http;

    protected AbstractModelApiClient(ClientConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    AbstractModelApiClient(ClientConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
        this.json = new ObjectMapper();
    }

    @Override
    public final ModelResponse complete(String question) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint()))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(question), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException error) {
            throw new ModelApiException("连接模型服务失败：" + error.getMessage(), 0, "");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelApiException("模型调用被中断。", 0, "");
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ModelApiException(
                    "模型服务返回 HTTP " + response.statusCode(),
                    response.statusCode(),
                    response.body()
            );
        }
        return parseResponse(response.body());
    }

    @Override
    public final void close() {
        // JDK HttpClient 无须显式释放连接资源。
    }

    protected abstract ModelResponse parseResponse(String body);
}
