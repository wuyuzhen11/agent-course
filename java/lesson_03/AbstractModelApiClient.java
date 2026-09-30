import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

abstract class AbstractModelApiClient implements ModelClient {
    protected final ClientConfig config;
    protected final ObjectMapper json;
    private final HttpClient http;

    private static final int MAX_HTTP_ATTEMPTS = 3;
    private static final long BASE_DELAY_MILLIS = 500;
    private static final long MAX_DELAY_MILLIS = 4_000;

    private boolean isRetryableStatus(int status) {
        return switch (status) {
            case 429, 500, 502, 503, 504 -> true;
            default -> false;
        };
    }

    AbstractModelApiClient(ClientConfig config) {
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
             attempt <= MAX_HTTP_ATTEMPTS;
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

                if (!isRetryableStatus(status)
                        || attempt == MAX_HTTP_ATTEMPTS) {
                    throw new ModelApiException(
                            "模型服务返回 HTTP " + status,
                            status,
                            response.body()
                    );
                }

                waitBeforeRetry(attempt, response);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ModelApiException(
                        "模型调用被中断。",
                        0,
                        "",
                        error
                );
            } catch (IOException error) {
                if (attempt == MAX_HTTP_ATTEMPTS) {
                    throw new ModelApiException(
                            "连接模型服务失败：" + error.getMessage(),
                            0,
                            "",
                            error
                    );
                }

                waitBeforeRetry(attempt);
            }
        }

        throw new IllegalStateException("HTTP 重试循环异常结束");
    }

    private void waitBeforeRetry(
            int attempt,
            HttpResponse<String> response
    ) {
        Optional<Long> retryAfterMillis = parseRetryAfterMillis(
                response.headers().firstValue("Retry-After")
        );

        if (retryAfterMillis.isPresent()) {
            long requiredDelay = retryAfterMillis.get();

            if (requiredDelay > MAX_DELAY_MILLIS) {
                throw new ModelApiException(
                        "Retry-After 超过最大等待时间",
                        response.statusCode(),
                        response.body()
                );
            }

            sleepWithJitter(requiredDelay);
            return;
        }

        waitBeforeRetry(attempt);
    }

    private void waitBeforeRetry(int attempt) {
        long exponentialDelay = Math.min(
                MAX_DELAY_MILLIS,
                BASE_DELAY_MILLIS * (1L << (attempt - 1))
        );

        sleepWithJitter(exponentialDelay);
    }

    private Optional<Long> parseRetryAfterMillis(
            Optional<String> retryAfter
    ) {
        if (retryAfter.isEmpty()) {
            return Optional.empty();
        }

        try {
            long seconds = Long.parseLong(retryAfter.get().trim());
            if (seconds < 0) {
                return Optional.empty();
            }

            return Optional.of(Math.multiplyExact(seconds, 1_000L));
        } catch (NumberFormatException | ArithmeticException error) {
            return Optional.empty();
        }
    }

    private void sleepWithJitter(long baseDelayMillis) {
        long jitterMillis = ThreadLocalRandom.current()
                .nextLong(0, 251);

        long delayMillis = Math.min(
                MAX_DELAY_MILLIS,
                baseDelayMillis + jitterMillis
        );

        try {
            Thread.sleep(delayMillis);
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
