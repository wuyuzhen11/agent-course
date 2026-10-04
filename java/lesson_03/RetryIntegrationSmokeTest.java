import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

final class RetryIntegrationSmokeTest {
    public static void main(String[] args) throws Exception {
        testRetryableResponse();
        testNonRetryableResponse();
        testIoExceptionRetry();
        testNonRetryableIoException();
        testExponentialBackoffPolicyContract();
        testResponsesClientRetryAndParse();
        testChatCompletionsClientRetryAndParse();
        System.out.println("RetryIntegrationSmokeTest passed");
    }

    private static void testExponentialBackoffPolicyContract() {
        ExponentialBackoffRetryPolicy policy =
                new ExponentialBackoffRetryPolicy();

        require(policy.maxAttempts() == 3, "最大重试次数错误");
        require(policy.isRetryableStatus(429), "429 应该可重试");
        require(policy.isRetryableStatus(500), "500 应该可重试");
        require(policy.isRetryableStatus(502), "502 应该可重试");
        require(policy.isRetryableStatus(503), "503 应该可重试");
        require(policy.isRetryableStatus(504), "504 应该可重试");
        require(!policy.isRetryableStatus(400), "400 不应该可重试");
        require(!policy.isRetryableStatus(401), "401 不应该可重试");

        requireBetween(
                policy.delay(1, Optional.empty()),
                500,
                750,
                "第一次退避范围错误"
        );
        requireBetween(
                policy.delay(2, Optional.empty()),
                1_000,
                1_250,
                "第二次退避范围错误"
        );
        requireBetween(
                policy.delay(3, Optional.empty()),
                2_000,
                2_250,
                "第三次退避范围错误"
        );
        requireBetween(
                policy.delay(1, Optional.of("5")),
                4_000,
                4_000,
                "Retry-After 上限处理错误"
        );
        requireBetween(
                policy.delay(1, Optional.of("tomorrow")),
                500,
                750,
                "无效 Retry-After 没有回退到指数退避"
        );

        try {
            policy.delay(0, Optional.empty());
            throw new AssertionError("attempt 为 0 时应该抛出异常");
        } catch (IllegalArgumentException expected) {
            // Expected contract violation.
        }
    }

    private static void requireBetween(
            Duration value,
            long minimumMillis,
            long maximumMillis,
            String message
    ) {
        long actualMillis = value.toMillis();
        require(
                actualMillis >= minimumMillis
                        && actualMillis <= maximumMillis,
                message + "，实际值：" + actualMillis
        );
    }

    private static void testResponsesClientRetryAndParse() {
        FakeHttpClient http = new FakeHttpClient(
                response(
                        503,
                        "busy",
                        Map.of("Retry-After", List.of("0"))
                ),
                response(
                        200,
                        "{\"output\":[{\"type\":\"message\","
                                + "\"content\":[{\"type\":\"output_text\","
                                + "\"text\":\"order is shipped\"}]}]}",
                        Map.of()
                )
        );
        RecordingRetryPolicy policy = new RecordingRetryPolicy(true);
        ResponsesApiClient client = new ResponsesApiClient(
                new ClientConfig(
                        "https://example.test/v1",
                        "test-key",
                        "test-model"
                ),
                http,
                policy
        );

        ModelTurn turn = client.next(
                List.of(new UserMessage("test")),
                List.of()
        );

        require(http.sendCount == 2, "Responses 客户端应该重试一次");
        require(policy.attempts.equals(List.of(1)),
                "Responses 客户端重试次数记录错误");
        require("order is shipped".equals(turn.text()),
                "Responses 响应解析结果错误");
    }

    private static void testChatCompletionsClientRetryAndParse() {
        FakeHttpClient http = new FakeHttpClient(
                response(
                        503,
                        "busy",
                        Map.of("Retry-After", List.of("0"))
                ),
                response(
                        200,
                        "{\"choices\":[{\"message\":{"
                                + "\"role\":\"assistant\","
                                + "\"content\":\"order is shipped\"}}]}",
                        Map.of()
                )
        );
        RecordingRetryPolicy policy = new RecordingRetryPolicy(true);
        ChatCompletionsApiClient client = new ChatCompletionsApiClient(
                new ClientConfig(
                        "https://example.test/v1",
                        "test-key",
                        "test-model"
                ),
                http,
                policy
        );

        ModelTurn turn = client.next(
                List.of(new UserMessage("test")),
                List.of()
        );

        require(http.sendCount == 2,
                "Chat Completions 客户端应该重试一次");
        require(policy.attempts.equals(List.of(1)),
                "Chat Completions 客户端重试次数记录错误");
        require("order is shipped".equals(turn.text()),
                "Chat Completions 响应解析结果错误");
    }

    private static void testRetryableResponse() {
        FakeHttpClient http = new FakeHttpClient(
                response(503, "busy", Map.of("Retry-After", List.of("0"))),
                response(200, "ok", Map.of())
        );
        RecordingRetryPolicy policy = new RecordingRetryPolicy(true);

        new TestClient(http, policy).next(List.of(new UserMessage("test")), List.of());

        require(http.sendCount == 2, "503 应该重试一次");
        require(policy.attempts.equals(List.of(1)), "重试次数记录错误");
        require(policy.retryAfter.equals(List.of(Optional.of("0"))),
                "Retry-After 没有传给策略");
    }

    private static void testNonRetryableResponse() {
        FakeHttpClient http = new FakeHttpClient(
                response(400, "bad request", Map.of())
        );
        RecordingRetryPolicy policy = new RecordingRetryPolicy(true);

        try {
            new TestClient(http, policy).next(
                    List.of(new UserMessage("test")),
                    List.of()
            );
            throw new AssertionError("400 应该抛出 ModelApiException");
        } catch (ModelApiException error) {
            require(error.httpStatus() == 400, "异常状态码错误");
        }

        require(http.sendCount == 1, "400 不应该重试");
        require(policy.attempts.isEmpty(), "400 不应该调用 delay");
    }

    private static void testIoExceptionRetry() {
        FakeHttpClient http = new FakeHttpClient(
                new IOException("temporary failure"),
                response(200, "ok", Map.of())
        );
        RecordingRetryPolicy policy = new RecordingRetryPolicy(true);

        new TestClient(http, policy).next(List.of(new UserMessage("test")), List.of());

        require(http.sendCount == 2, "IOException 应该重试一次");
        require(policy.attempts.equals(List.of(1)), "IOException 重试次数记录错误");
        require(policy.retryAfter.equals(List.of(Optional.empty())),
                "IOException 不应该伪造 Retry-After");
    }

    private static void testNonRetryableIoException() {
        FakeHttpClient http = new FakeHttpClient(
                new IOException("permanent failure")
        );
        RecordingRetryPolicy policy = new RecordingRetryPolicy(false);

        try {
            new TestClient(http, policy).next(
                    List.of(new UserMessage("test")),
                    List.of()
            );
            throw new AssertionError(
                    "策略拒绝 IOException 时应该抛出 ModelApiException"
            );
        } catch (ModelApiException error) {
            require(error.httpStatus() == 0, "IOException 异常状态码错误");
        }

        require(http.sendCount == 1, "不可重试 IOException 不应该重试");
        require(policy.attempts.isEmpty(), "不可重试 IOException 不应该调用 delay");
    }

    private static HttpResponse<String> response(
            int status,
            String body,
            Map<String, List<String>> headers
    ) {
        return new FakeHttpResponse(
                status,
                body,
                HttpHeaders.of(headers, (name, value) -> true)
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class RecordingRetryPolicy
            implements RetryPolicy {
        private final boolean retryableException;
        private final List<Integer> attempts = new ArrayList<>();
        private final List<Optional<String>> retryAfter = new ArrayList<>();

        private RecordingRetryPolicy(boolean retryableException) {
            this.retryableException = retryableException;
        }

        @Override
        public int maxAttempts() {
            return 3;
        }

        @Override
        public boolean isRetryableStatus(int status) {
            return status == 503;
        }

        @Override
        public Duration delay(
                int attempt,
                Optional<String> retryAfter
        ) {
            attempts.add(attempt);
            this.retryAfter.add(retryAfter);
            return Duration.ZERO;
        }

        @Override
        public boolean isRetryableException(IOException error) {
            return retryableException;
        }
    }

    private static final class TestClient
            extends AbstractModelApiClient {
        private TestClient(
                HttpClient http,
                RetryPolicy retryPolicy
        ) {
            super(
                    new ClientConfig(
                            "https://example.test/v1",
                            "test-key",
                            "test-model"
                    ),
                    http,
                    retryPolicy
            );
        }

        @Override
        String endpoint() {
            return "https://example.test/v1/responses";
        }

        @Override
        String buildRequestBody(
                String model,
                List<Message> history,
                List<ToolDefinition> toolDefinitions
        ) {
            return "{}";
        }

        @Override
        ModelTurn parseResponse(String body) {
            return new ModelTurn(body, List.of());
        }
    }

    private static final class FakeHttpClient extends HttpClient {
        private final Queue<Object> outcomes = new ArrayDeque<>();
        private int sendCount;

        private FakeHttpClient(Object... outcomes) {
            for (Object outcome : outcomes) {
                this.outcomes.add(outcome);
            }
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) throws IOException {
            sendCount++;
            Object outcome = outcomes.remove();
            if (outcome instanceof IOException error) {
                throw error;
            }
            return (HttpResponse<T>) outcome;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException()
            );
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException()
            );
        }

        @Override
        public WebSocket.Builder newWebSocketBuilder() {
            return null;
        }
    }

    private record FakeHttpResponse(
            int statusCode,
            String body,
            HttpHeaders headers
    ) implements HttpResponse<String> {
        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://example.test/v1/responses");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
