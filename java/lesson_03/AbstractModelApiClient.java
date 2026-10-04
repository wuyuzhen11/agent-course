import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

abstract class AbstractModelApiClient implements ModelClient {
    protected final ClientConfig config;
    protected final ObjectMapper json;
    private final HttpClient http;
    private final RetryPolicy retryPolicy;

    AbstractModelApiClient(
            ClientConfig config,
            RetryPolicy retryPolicy
    ) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), retryPolicy);
    }

    AbstractModelApiClient(
            ClientConfig config,
            HttpClient http,
            RetryPolicy retryPolicy
    ) {
        this.config = config;
        this.http = http;
        this.json = new ObjectMapper();
        this.retryPolicy = Objects.requireNonNull(
                retryPolicy,
                "retryPolicy"
        );
    }

    @Override
    public final ModelTurn next(
            List<Message> history,
            List<ToolDefinition> toolDefinitions
    ) {
        config.requireApiKey();

        String requestBody = buildRequestBody(
                config.model(),
                history,
                toolDefinitions
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint()))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestBody,
                        StandardCharsets.UTF_8
                ))
                .build();

        HttpResponse<String> response = sendWithRetry(request);

        try {
            return parseResponse(response.body());
        } catch (ModelApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ModelApiException(
                    "模型响应解析失败：" + error.getMessage(),
                    response.statusCode(),
                    response.body(),
                    error
            );
        }
    }
    private HttpResponse<String> sendWithRetry(HttpRequest request) {
        for (int attempt = 1;
             attempt <= retryPolicy.maxAttempts();
             attempt++) {
            try {
                HttpResponse<String> response = http.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8
                        )
                );

                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    return response;
                }

                if (!retryPolicy.isRetryableStatus(status)
                        || attempt == retryPolicy.maxAttempts()) {
                    throw new ModelApiException(
                            "模型服务返回 HTTP " + status,
                            status,
                            response.body()
                    );
                }
                waitBeforeRetry(retryPolicy.delay(
                        attempt,
                        response.headers().firstValue("Retry-After")
                ));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ModelApiException(
                        "模型调用被中断。",
                        0,
                        "",
                        error
                );
            } catch (IOException error) {
                if (!retryPolicy.isRetryableException(error)
                        || attempt == retryPolicy.maxAttempts()) {
                    throw new ModelApiException(
                            "连接模型服务失败：" + error.getMessage(),
                            0,
                            "",
                            error
                    );
                }

                waitBeforeRetry(retryPolicy.delay(
                        attempt,
                        Optional.empty()
                ));
            }
        }

        throw new IllegalStateException("HTTP 重试循环异常结束");
    }

    private void waitBeforeRetry(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelApiException(
                    "重试等待被中断。",
                    0,
                    "",
                    error
            );
        }
    }

    abstract String endpoint();

    abstract String buildRequestBody(
            String model,
            List<Message> history,
            List<ToolDefinition> toolDefinitions
    );

    abstract ModelTurn parseResponse(String body);
}
