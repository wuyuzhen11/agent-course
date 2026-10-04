import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
        testRetryableStatusExhausted();
        testIoExceptionExhausted();
        testSendInterrupted();
        testRetryWaitInterrupted();
        testSuccessfulResponseParseFailure();
        testExponentialBackoffPolicyContract();
        testResponsesClientRetryAndParse();
        testChatCompletionsClientRetryAndParse();
        testResponsesRequestShape();
        testChatCompletionsRequestShape();
        testResponsesToolHistory();
        testChatCompletionsToolHistory();
        testMultipleToolCallsRoundTrip();
        testMalformedProtocolResponses();
        testAgentLoopMultiToolSuccess();
        testAgentLoopRejectsMismatchedToolResult();
        testAgentLoopRejectsDuplicateCallId();
        testAgentLoopRejectsEmptyResponse();
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
        require(policy.isRetryableException(new IOException("temporary")),
                "默认策略应该重试 IOException");

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
        requireBetween(
                policy.delay(1, Optional.of("-1")),
                500,
                750,
                "负数 Retry-After 没有回退到指数退避"
        );
        requireBetween(
                policy.delay(1, Optional.of("9223372036854775807")),
                500,
                750,
                "溢出 Retry-After 没有回退到指数退避"
        );
        requireBetween(
                policy.delay(1, Optional.of(" 2 ")),
                2_000,
                2_250,
                "数字 Retry-After 解析错误"
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

    private static void testResponsesRequestShape() {
        ResponsesApiClient client = new ResponsesApiClient(
                testConfig(),
                new FakeHttpClient(),
                new RecordingRetryPolicy(true)
        );
        ToolDefinition tool = testTool();

        String body = client.buildRequestBody(
                "test-model",
                List.of(new UserMessage("query order 123")),
                List.of(tool)
        );

        try {
            JsonNode root = new ObjectMapper().readTree(body);
            JsonNode input = root.get("input");
            JsonNode tools = root.get("tools");
            JsonNode requestTool = tools.get(0);

            require("test-model".equals(root.path("model").asText()),
                    "Responses model 字段错误");
            require(input != null && input.isArray(),
                    "Responses 缺少 input 数组");
            require(!root.has("messages"),
                    "Responses 不应该使用 messages 字段");
            require("user".equals(input.get(0).path("role").asText()),
                    "Responses user role 错误");
            require("query order 123".equals(
                            input.get(0).path("content").asText()),
                    "Responses user content 错误");
            require("function".equals(requestTool.path("type").asText()),
                    "Responses tool type 错误");
            require("get_order".equals(requestTool.path("name").asText()),
                    "Responses tool name 错误");
            require(requestTool.path("parameters").isObject(),
                    "Responses parameters 必须是对象");
            require(requestTool.path("strict").asBoolean(),
                    "Responses strict 字段错误");
        } catch (Exception error) {
            throw new AssertionError("Responses 请求 JSON 解析失败", error);
        }
    }

    private static void testChatCompletionsRequestShape() {
        ChatCompletionsApiClient client = new ChatCompletionsApiClient(
                testConfig(),
                new FakeHttpClient(),
                new RecordingRetryPolicy(true)
        );
        ToolDefinition tool = testTool();

        String body = client.buildRequestBody(
                "test-model",
                List.of(new UserMessage("query order 123")),
                List.of(tool)
        );

        try {
            JsonNode root = new ObjectMapper().readTree(body);
            JsonNode messages = root.get("messages");
            JsonNode tools = root.get("tools");
            JsonNode requestTool = tools.get(0);
            JsonNode function = requestTool.get("function");

            require("test-model".equals(root.path("model").asText()),
                    "Chat Completions model 字段错误");
            require(messages != null && messages.isArray(),
                    "Chat Completions 缺少 messages 数组");
            require(!root.has("input"),
                    "Chat Completions 不应该使用 input 字段");
            require("user".equals(messages.get(0).path("role").asText()),
                    "Chat Completions user role 错误");
            require("query order 123".equals(
                            messages.get(0).path("content").asText()),
                    "Chat Completions user content 错误");
            require("function".equals(requestTool.path("type").asText()),
                    "Chat Completions tool type 错误");
            require(function != null && function.isObject(),
                    "Chat Completions 缺少 function 对象");
            require("get_order".equals(function.path("name").asText()),
                    "Chat Completions tool name 错误");
            require(function.path("parameters").isObject(),
                    "Chat Completions parameters 必须是对象");
            require(function.path("strict").asBoolean(),
                    "Chat Completions strict 字段错误");
        } catch (Exception error) {
            throw new AssertionError(
                    "Chat Completions 请求 JSON 解析失败",
                    error
            );
        }
    }

    private static ClientConfig testConfig() {
        return new ClientConfig(
                "https://example.test/v1",
                "test-key",
                "test-model"
        );
    }

    private static ToolDefinition testTool() {
        return new ToolDefinition(
                "get_order",
                "query order",
                "{\"type\":\"object\","
                        + "\"properties\":{\"orderId\":{"
                        + "\"type\":\"string\"}}}",
                true
        );
    }

    private static void testResponsesToolHistory() {
        ResponsesApiClient client = new ResponsesApiClient(
                testConfig(),
                new FakeHttpClient(),
                new RecordingRetryPolicy(true)
        );
        String responseBody =
                "{\"output\":[{\"type\":\"function_call\","
                        + "\"id\":\"fc_1\","
                        + "\"call_id\":\"call-123\","
                        + "\"name\":\"get_order\","
                        + "\"arguments\":\"{\\\"orderId\\\":\\\"123\\\"}\"}]}";

        ModelTurn modelTurn = client.parseResponse(responseBody);
        require(modelTurn.toolCalls().size() == 1,
                "Responses 工具调用数量错误");
        require("call-123".equals(modelTurn.toolCalls().get(0).callId()),
                "Responses call_id 解析错误");

        String body = client.buildRequestBody(
                "test-model",
                List.of(
                        new UserMessage("query order 123"),
                        modelTurn,
                        new ToolResult("call-123", "shipped")
                ),
                List.of()
        );

        try {
            JsonNode input = new ObjectMapper()
                    .readTree(body)
                    .path("input");

            require(input.isArray() && input.size() == 3,
                    "Responses 历史消息数量错误");
            require("user".equals(input.get(0).path("role").asText()),
                    "Responses 用户消息顺序错误");
            require("function_call".equals(
                            input.get(1).path("type").asText()),
                    "Responses function_call 顺序错误");
            require("fc_1".equals(input.get(1).path("id").asText()),
                    "Responses 原始 output item 的 id 未保留");
            require("call-123".equals(
                            input.get(1).path("call_id").asText()),
                    "Responses function_call 的 call_id 错误");
            require("function_call_output".equals(
                            input.get(2).path("type").asText()),
                    "Responses 工具结果顺序错误");
            require("call-123".equals(
                            input.get(2).path("call_id").asText()),
                    "Responses 工具结果 call_id 错误");
            require("shipped".equals(input.get(2).path("output").asText()),
                    "Responses 工具结果内容错误");
        } catch (Exception error) {
            throw new AssertionError("Responses 工具历史序列化失败", error);
        }
    }

    private static void testChatCompletionsToolHistory() {
        ChatCompletionsApiClient client = new ChatCompletionsApiClient(
                testConfig(),
                new FakeHttpClient(),
                new RecordingRetryPolicy(true)
        );
        String responseBody =
                "{\"choices\":[{\"message\":{"
                        + "\"role\":\"assistant\","
                        + "\"content\":null,"
                        + "\"tool_calls\":[{"
                        + "\"id\":\"call-123\","
                        + "\"type\":\"function\","
                        + "\"function\":{"
                        + "\"name\":\"get_order\","
                        + "\"arguments\":\"{\\\"orderId\\\":\\\"123\\\"}\""
                        + "}}]}}]}";

        ModelTurn modelTurn = client.parseResponse(responseBody);
        require(modelTurn.toolCalls().size() == 1,
                "Chat Completions 工具调用数量错误");
        require("call-123".equals(modelTurn.toolCalls().get(0).callId()),
                "Chat Completions call_id 解析错误");

        String body = client.buildRequestBody(
                "test-model",
                List.of(
                        new UserMessage("query order 123"),
                        modelTurn,
                        new ToolResult("call-123", "shipped")
                ),
                List.of()
        );

        try {
            JsonNode messages = new ObjectMapper()
                    .readTree(body)
                    .path("messages");
            JsonNode assistant = messages.get(1);
            JsonNode toolCall = assistant.path("tool_calls").get(0);
            JsonNode function = toolCall.path("function");
            JsonNode toolResult = messages.get(2);

            require(messages.isArray() && messages.size() == 3,
                    "Chat Completions 历史消息数量错误");
            require("user".equals(messages.get(0).path("role").asText()),
                    "Chat Completions 用户消息顺序错误");
            require("assistant".equals(assistant.path("role").asText()),
                    "Chat Completions assistant 消息顺序错误");
            require(assistant.path("content").isNull(),
                    "Chat Completions 工具调用消息 content 应为 null");
            require("call-123".equals(toolCall.path("id").asText()),
                    "Chat Completions tool call id 错误");
            require("function".equals(toolCall.path("type").asText()),
                    "Chat Completions tool call type 错误");
            require("get_order".equals(function.path("name").asText()),
                    "Chat Completions 工具名称错误");
            require("tool".equals(toolResult.path("role").asText()),
                    "Chat Completions 工具结果 role 错误");
            require("call-123".equals(
                            toolResult.path("tool_call_id").asText()),
                    "Chat Completions 工具结果 call_id 错误");
            require("shipped".equals(toolResult.path("content").asText()),
                    "Chat Completions 工具结果内容错误");
        } catch (Exception error) {
            throw new AssertionError(
                    "Chat Completions 工具历史序列化失败",
                    error
            );
        }
    }

    private static void testMultipleToolCallsRoundTrip() {
        ResponsesApiClient responses = new ResponsesApiClient(
                testConfig(),
                new FakeHttpClient(),
                new RecordingRetryPolicy(true)
        );
        ModelTurn responsesTurn = responses.parseResponse(
                "{\"output\":["
                        + "{\"type\":\"function_call\","
                        + "\"id\":\"fc_1\",\"call_id\":\"call-1\","
                        + "\"name\":\"get_order\","
                        + "\"arguments\":\"{\\\"orderId\\\":\\\"123\\\"}\"},"
                        + "{\"type\":\"function_call\","
                        + "\"id\":\"fc_2\",\"call_id\":\"call-2\","
                        + "\"name\":\"get_order\","
                        + "\"arguments\":\"{\\\"orderId\\\":\\\"456\\\"}\"}"
                        + "]}"
        );
        require(responsesTurn.toolCalls().size() == 2,
                "Responses 多工具调用数量错误");
        JsonNode responsesInput = parseJson(
                responses.buildRequestBody(
                        "test-model",
                        List.of(
                                new UserMessage("query orders"),
                                responsesTurn,
                                new ToolResult("call-1", "shipped"),
                                new ToolResult("call-2", "pending")
                        ),
                        List.of()
                )
        ).path("input");
        require(responsesInput.size() == 5,
                "Responses 多工具历史数量错误");
        require("call-1".equals(
                        responsesInput.get(1).path("call_id").asText()),
                "Responses 第一个工具调用顺序错误");
        require("call-2".equals(
                        responsesInput.get(2).path("call_id").asText()),
                "Responses 第二个工具调用顺序错误");
        require("call-1".equals(
                        responsesInput.get(3).path("call_id").asText()),
                "Responses 第一个工具结果错位");
        require("call-2".equals(
                        responsesInput.get(4).path("call_id").asText()),
                "Responses 第二个工具结果错位");

        ChatCompletionsApiClient chat = new ChatCompletionsApiClient(
                testConfig(),
                new FakeHttpClient(),
                new RecordingRetryPolicy(true)
        );
        ModelTurn chatTurn = chat.parseResponse(
                "{\"choices\":[{\"message\":{"
                        + "\"role\":\"assistant\",\"content\":null,"
                        + "\"tool_calls\":["
                        + "{\"id\":\"call-1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"get_order\","
                        + "\"arguments\":\"{\\\"orderId\\\":\\\"123\\\"}\"}},"
                        + "{\"id\":\"call-2\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"get_order\","
                        + "\"arguments\":\"{\\\"orderId\\\":\\\"456\\\"}\"}}"
                        + "]}}]}"
        );
        require(chatTurn.toolCalls().size() == 2,
                "Chat Completions 多工具调用数量错误");
        JsonNode chatMessages = parseJson(
                chat.buildRequestBody(
                        "test-model",
                        List.of(
                                new UserMessage("query orders"),
                                chatTurn,
                                new ToolResult("call-1", "shipped"),
                                new ToolResult("call-2", "pending")
                        ),
                        List.of()
                )
        ).path("messages");
        require(chatMessages.size() == 4,
                "Chat Completions 多工具历史数量错误");
        require(chatMessages.get(1).path("tool_calls").size() == 2,
                "Chat Completions 多工具调用没有保留");
        require("call-1".equals(
                        chatMessages.get(2).path("tool_call_id").asText()),
                "Chat Completions 第一个工具结果错位");
        require("call-2".equals(
                        chatMessages.get(3).path("tool_call_id").asText()),
                "Chat Completions 第二个工具结果错位");
    }

    private static void testMalformedProtocolResponses() {
        ResponsesApiClient responses = new ResponsesApiClient(
                testConfig(),
                new FakeHttpClient(),
                new RecordingRetryPolicy(true)
        );
        requireThrows(
                IllegalArgumentException.class,
                () -> responses.parseResponse(
                        "{\"output\":[{\"type\":\"function_call\","
                                + "\"name\":\"get_order\","
                                + "\"arguments\":\"{}\"}] }"
                ),
                "Responses 缺少 call_id 应该失败"
        );

        ChatCompletionsApiClient chat = new ChatCompletionsApiClient(
                testConfig(),
                new FakeHttpClient(),
                new RecordingRetryPolicy(true)
        );
        requireThrows(
                IllegalArgumentException.class,
                () -> chat.parseResponse(
                        "{\"choices\":[{\"message\":{"
                                + "\"content\":null,\"tool_calls\":[{"
                                + "\"type\":\"function\","
                                + "\"function\":{\"name\":\"get_order\","
                                + "\"arguments\":\"{}\"}}]}}]}"
                ),
                "Chat Completions 缺少工具 id 应该失败"
        );
    }

    private static void testAgentLoopMultiToolSuccess() {
        SequenceModelClient model = new SequenceModelClient(
                new ModelTurn(
                        null,
                        List.of(
                                new ToolCall("call-1", "get_order", "{}"),
                                new ToolCall("call-2", "get_order", "{}")
                        )
                ),
                new ModelTurn("done", List.of())
        );
        RecordingToolExecutor tools = new RecordingToolExecutor(false, false);

        String answer = new AgentLoop(
                model,
                tools,
                new RunLimits(3, 3, 2)
        ).run("query orders");

        require("done".equals(answer), "多工具 Agent 最终回答错误");
        require(model.calls == 2, "多工具 Agent 模型调用次数错误");
        require(tools.callIds.equals(List.of("call-1", "call-2")),
                "多工具执行顺序错误");
        require(model.historySizes.equals(List.of(1, 4)),
                "工具结果没有完整回填到下一轮历史");
    }

    private static void testAgentLoopRejectsMismatchedToolResult() {
        SequenceModelClient model = new SequenceModelClient(
                new ModelTurn(
                        null,
                        List.of(new ToolCall("call-1", "get_order", "{}"))
                )
        );
        RecordingToolExecutor tools = new RecordingToolExecutor(true, false);

        requireThrows(
                IllegalStateException.class,
                () -> new AgentLoop(
                        model,
                        tools,
                        new RunLimits(2, 1, 1)
                ).run("query order"),
                "错配 callId 应该被 AgentLoop 拒绝"
        );
        require(model.calls == 1, "错配 callId 后不应该继续调用模型");
        require(tools.callIds.equals(List.of("call-1")),
                "错配 callId 前工具调用次数错误");
    }

    private static void testAgentLoopRejectsDuplicateCallId() {
        SequenceModelClient model = new SequenceModelClient(
                new ModelTurn(
                        null,
                        List.of(
                                new ToolCall("call-1", "get_order", "{}"),
                                new ToolCall("call-1", "get_order", "{}")
                        )
                )
        );
        RecordingToolExecutor tools = new RecordingToolExecutor(false, false);

        requireThrows(
                IllegalStateException.class,
                () -> new AgentLoop(
                        model,
                        tools,
                        new RunLimits(2, 2, 2)
                ).run("query order"),
                "重复 callId 应该被 AgentLoop 拒绝"
        );
        require(tools.callIds.isEmpty(),
                "重复 callId 应该在执行工具前被拒绝");
    }

    private static void testAgentLoopRejectsEmptyResponse() {
        SequenceModelClient model = new SequenceModelClient(
                new ModelTurn(null, List.of())
        );
        requireThrows(
                IllegalStateException.class,
                () -> new AgentLoop(
                        model,
                        new RecordingToolExecutor(false, false),
                        new RunLimits(1, 1, 1)
                ).run("query order"),
                "空模型响应应该被拒绝"
        );
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

    private static void testRetryableStatusExhausted() {
        FakeHttpClient http = new FakeHttpClient(
                response(503, "first", Map.of()),
                response(503, "second", Map.of()),
                response(503, "last", Map.of())
        );
        RecordingRetryPolicy policy = new RecordingRetryPolicy(true);

        try {
            new TestClient(http, policy).next(
                    List.of(new UserMessage("test")),
                    List.of()
            );
            throw new AssertionError("503 用尽次数后应该抛出异常");
        } catch (ModelApiException error) {
            require(error.httpStatus() == 503, "最终 HTTP 状态码错误");
            require("last".equals(error.responseBody()),
                    "应该保留最后一次 HTTP 响应体");
        }

        require(http.sendCount == 3, "503 最多只能发送 3 次");
        require(policy.attempts.equals(List.of(1, 2)),
                "最后一次 503 不应该再计算等待");
    }

    private static void testIoExceptionExhausted() {
        FakeHttpClient http = new FakeHttpClient(
                new IOException("first"),
                new IOException("second"),
                new IOException("last")
        );
        RecordingRetryPolicy policy = new RecordingRetryPolicy(true);

        try {
            new TestClient(http, policy).next(
                    List.of(new UserMessage("test")),
                    List.of()
            );
            throw new AssertionError("IOException 用尽次数后应该抛出异常");
        } catch (ModelApiException error) {
            require(error.httpStatus() == 0, "IOException 不应有 HTTP 状态码");
            require(error.getCause() instanceof IOException,
                    "应该保留 IOException 原因");
            require("last".equals(error.getCause().getMessage()),
                    "应该保留最后一次 IOException");
        }

        require(http.sendCount == 3, "IOException 最多只能发送 3 次");
        require(policy.attempts.equals(List.of(1, 2)),
                "最后一次 IOException 不应该再计算等待");
    }

    private static void testSendInterrupted() {
        FakeHttpClient http = new FakeHttpClient(
                new InterruptedException("send interrupted")
        );

        try {
            try {
                new TestClient(http, new RecordingRetryPolicy(true)).next(
                        List.of(new UserMessage("test")),
                        List.of()
                );
                throw new AssertionError("发送中断应该抛出异常");
            } catch (ModelApiException error) {
                require(error.getCause() instanceof InterruptedException,
                        "发送中断原因丢失");
                require(Thread.currentThread().isInterrupted(),
                        "发送中断标记没有恢复");
            }
            require(http.sendCount == 1, "发送中断后不应该重试");
        } finally {
            Thread.interrupted();
        }
    }

    private static void testRetryWaitInterrupted() {
        FakeHttpClient http = new FakeHttpClient(
                response(503, "busy", Map.of()),
                response(200, "ok", Map.of())
        );
        RecordingRetryPolicy policy = new RecordingRetryPolicy(true);

        try {
            Thread.currentThread().interrupt();
            try {
                new TestClient(http, policy).next(
                        List.of(new UserMessage("test")),
                        List.of()
                );
                throw new AssertionError("重试等待中断应该抛出异常");
            } catch (ModelApiException error) {
                require(error.getCause() instanceof InterruptedException,
                        "等待中断原因丢失");
                require(Thread.currentThread().isInterrupted(),
                        "等待中断标记没有恢复");
            }
            require(http.sendCount == 1, "等待中断后不应该再次发送");
            require(policy.attempts.equals(List.of(1)),
                    "等待前应该计算一次退避");
        } finally {
            Thread.interrupted();
        }
    }

    private static void testSuccessfulResponseParseFailure() {
        FakeHttpClient responsesHttp = new FakeHttpClient(
                response(200, "{\"output\":null}", Map.of())
        );
        ResponsesApiClient responses = new ResponsesApiClient(
                testConfig(), responsesHttp, new RecordingRetryPolicy(true)
        );
        try {
            responses.next(List.of(new UserMessage("test")), List.of());
            throw new AssertionError("Responses 坏响应应该解析失败");
        } catch (ModelApiException error) {
            require(error.httpStatus() == 200, "Responses 解析错误状态码丢失");
            require("{\"output\":null}".equals(error.responseBody()),
                    "Responses 解析错误响应体丢失");
        }
        require(responsesHttp.sendCount == 1, "Responses 解析错误不应重试");

        FakeHttpClient chatHttp = new FakeHttpClient(
                response(200, "{\"choices\":[]}", Map.of())
        );
        ChatCompletionsApiClient chat = new ChatCompletionsApiClient(
                testConfig(), chatHttp, new RecordingRetryPolicy(true)
        );
        try {
            chat.next(List.of(new UserMessage("test")), List.of());
            throw new AssertionError("Chat 坏响应应该解析失败");
        } catch (ModelApiException error) {
            require(error.httpStatus() == 200, "Chat 解析错误状态码丢失");
            require("{\"choices\":[]}".equals(error.responseBody()),
                    "Chat 解析错误响应体丢失");
        }
        require(chatHttp.sendCount == 1, "Chat 解析错误不应重试");
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

    private static JsonNode parseJson(String body) {
        try {
            return new ObjectMapper().readTree(body);
        } catch (JsonProcessingException error) {
            throw new AssertionError("测试 JSON 解析失败", error);
        }
    }

    private static void requireThrows(
            Class<? extends RuntimeException> expectedType,
            Runnable action,
            String message
    ) {
        try {
            action.run();
        } catch (RuntimeException error) {
            require(expectedType.isInstance(error),
                    message + "，实际异常：" + error.getClass().getName());
            return;
        }

        throw new AssertionError(message);
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

    private static final class SequenceModelClient
            implements ModelClient {
        private final Queue<ModelTurn> turns = new ArrayDeque<>();
        private final List<Integer> historySizes = new ArrayList<>();
        private int calls;

        private SequenceModelClient(ModelTurn... turns) {
            for (ModelTurn turn : turns) {
                this.turns.add(turn);
            }
        }

        @Override
        public ModelTurn next(
                List<Message> history,
                List<ToolDefinition> toolDefinitions
        ) {
            calls++;
            historySizes.add(history.size());
            return turns.remove();
        }
    }

    private static final class RecordingToolExecutor
            implements ToolExecutor {
        private final boolean mismatchResultCallId;
        private final boolean nullResult;
        private final List<String> callIds = new ArrayList<>();

        private RecordingToolExecutor(
                boolean mismatchResultCallId,
                boolean nullResult
        ) {
            this.mismatchResultCallId = mismatchResultCallId;
            this.nullResult = nullResult;
        }

        @Override
        public List<ToolDefinition> definitions() {
            return List.of(testTool());
        }

        @Override
        public ToolResult execute(RunContext context, ToolCall call) {
            callIds.add(call.callId());
            if (nullResult) {
                return null;
            }

            String resultCallId = mismatchResultCallId
                    ? "wrong-call-id"
                    : call.callId();
            return new ToolResult(resultCallId, "ok");
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
        ) throws IOException, InterruptedException {
            sendCount++;
            Object outcome = outcomes.remove();
            if (outcome instanceof IOException error) {
                throw error;
            }
            if (outcome instanceof InterruptedException error) {
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
