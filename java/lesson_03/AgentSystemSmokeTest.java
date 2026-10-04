import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

final class AgentSystemSmokeTest {
    public static void main(String[] args) {
        testRunLimitsValidation();
        testRunContextBudgetAndStatus();
        testOrderToolsValidation();
        testAgentLoopLimits();
        testModelClientAssembly();
        System.out.println("AgentSystemSmokeTest passed");
    }

    private static void testRunLimitsValidation() {
        requireThrows(
                IllegalArgumentException.class,
                () -> new RunLimits(0, 1, 1),
                "maxModelCalls 为 0 应该失败"
        );
        requireThrows(
                IllegalArgumentException.class,
                () -> new RunLimits(1, 0, 1),
                "maxToolCalls 为 0 应该失败"
        );
        requireThrows(
                IllegalArgumentException.class,
                () -> new RunLimits(1, 1, 0),
                "maxToolsPerTurn 为 0 应该失败"
        );

        RunLimits limits = new RunLimits(2, 3, 2);
        require(limits.maxModelCalls() == 2, "RunLimits 模型预算错误");
        require(limits.maxToolCalls() == 3, "RunLimits 工具预算错误");
        require(limits.maxToolsPerTurn() == 2,
                "RunLimits 单轮工具预算错误");
    }

    private static void testRunContextBudgetAndStatus() {
        RunContext context = RunContext.start("session-1", "question");
        RunLimits limits = new RunLimits(2, 2, 2);

        require(context.status() == RunStatus.RUNNING,
                "RunContext 初始状态错误");
        context.reserveModelCall(limits);
        context.reserveModelCall(limits);
        require(context.modelCalls() == 2, "模型调用计数错误");
        requireThrows(
                AgentLimitExceededException.class,
                () -> context.reserveModelCall(limits),
                "模型预算耗尽应该失败"
        );

        context.reserveToolCalls(2, limits);
        require(context.toolCalls() == 2, "工具调用计数错误");
        requireThrows(
                AgentLimitExceededException.class,
                () -> context.reserveToolCalls(1, limits),
                "工具预算耗尽应该失败"
        );

        context.append(new ToolResult("call-1", "ok"));
        require(context.history().size() == 2,
                "RunContext 历史追加错误");
        context.pause();
        require(context.status() == RunStatus.PAUSED,
                "RunContext 暂停状态错误");
        context.succeed();
        require(context.status() == RunStatus.SUCCEEDED,
                "RunContext 成功状态错误");
        context.fail();
        require(context.status() == RunStatus.FAILED,
                "RunContext 失败状态错误");
    }

    private static void testOrderToolsValidation() {
        OrderTools tools = new OrderTools();
        RunContext context = RunContext.start("session-1", "question");

        ToolResult found = tools.execute(
                context,
                new ToolCall("call-1", "get_order", "{\"orderId\":\"123\"}")
        );
        require(found.output().contains("订单 123"),
                "合法订单查询结果错误");
        require("call-1".equals(found.callId()),
                "工具结果 callId 没有保留");

        ToolResult missing = tools.execute(
                context,
                new ToolCall("call-2", "get_order", "{\"orderId\":\"999\"}")
        );
        require(missing.output().startsWith("ORDER_NOT_FOUND"),
                "不存在订单结果错误");

        List<ToolCall> invalidCalls = List.of(
                new ToolCall("call-3", "get_order", "{}"),
                new ToolCall("call-4", "get_order", "{\"orderId\":123}"),
                new ToolCall("call-5", "get_order", "{\"orderId\":\"123\",\"x\":1}"),
                new ToolCall("call-6", "get_order", "not-json"),
                new ToolCall("call-7", "get_order", ""),
                new ToolCall("call-8", "get_order", "[]")
        );
        for (ToolCall call : invalidCalls) {
            ToolResult result = tools.execute(context, call);
            require(result.output().contains("INVALID"),
                    "非法工具参数没有返回结构化错误：" + call.callId());
            require(call.callId().equals(result.callId()),
                    "非法参数结果 callId 错误：" + call.callId());
        }

        ToolResult unknown = tools.execute(
                context,
                new ToolCall("call-9", "delete_order", "{}")
        );
        require(unknown.output().startsWith("UNKNOWN_TOOL"),
                "未知工具结果错误");
    }

    private static void testAgentLoopLimits() {
        SequenceModelClient tooManyPerTurn = new SequenceModelClient(
                toolTurn("call-1", "call-2")
        );
        RecordingToolExecutor toolsPerTurn = new RecordingToolExecutor();
        requireThrows(
                AgentLimitExceededException.class,
                () -> new AgentLoop(
                        tooManyPerTurn,
                        toolsPerTurn,
                        new RunLimits(2, 3, 1)
                ).run("question"),
                "maxToolsPerTurn 应该生效"
        );
        require(toolsPerTurn.callIds.isEmpty(),
                "单轮工具超限时不应该执行工具");

        SequenceModelClient tooManyTotal = new SequenceModelClient(
                toolTurn("call-1", "call-2")
        );
        RecordingToolExecutor toolsTotal = new RecordingToolExecutor();
        requireThrows(
                AgentLimitExceededException.class,
                () -> new AgentLoop(
                        tooManyTotal,
                        toolsTotal,
                        new RunLimits(2, 1, 2)
                ).run("question"),
                "maxToolCalls 应该生效"
        );
        require(toolsTotal.callIds.isEmpty(),
                "总工具超限时不应该执行工具");

        SequenceModelClient tooManyModelCalls = new SequenceModelClient(
                new ModelTurn(
                        null,
                        List.of(new ToolCall("call-1", "get_order", "{}"))
                ),
                new ModelTurn("done", List.of())
        );
        RecordingToolExecutor toolsModel = new RecordingToolExecutor();
        requireThrows(
                AgentLimitExceededException.class,
                () -> new AgentLoop(
                        tooManyModelCalls,
                        toolsModel,
                        new RunLimits(1, 1, 1)
                ).run("question"),
                "maxModelCalls 应该生效"
        );
        require(tooManyModelCalls.calls == 1,
                "模型预算耗尽后不应该进入第二次模型调用");
    }

    private static void testModelClientAssembly() {
        ClientConfig config = new ClientConfig(
                "https://example.test/v1",
                "test-key",
                "test-model"
        );
        RetryPolicy policy = new ExponentialBackoffRetryPolicy();

        AbstractModelApiClient responses = ModelClients.create(
                config,
                ApiProtocol.RESPONSES,
                policy
        );
        AbstractModelApiClient chat = ModelClients.create(
                config,
                ApiProtocol.CHAT_COMPLETIONS,
                policy
        );
        require(responses.endpoint().endsWith("/responses"),
                "Responses 组装协议错误");
        require(chat.endpoint().endsWith("/chat/completions"),
                "Chat 组装协议错误");
        requireThrows(
                NullPointerException.class,
                () -> ModelClients.create(
                        config,
                        ApiProtocol.RESPONSES,
                        null
                ),
                "ModelClients 应该拒绝 null RetryPolicy"
        );
    }

    private static ModelTurn toolTurn(String firstCallId, String secondCallId) {
        return new ModelTurn(
                null,
                List.of(
                        new ToolCall(firstCallId, "get_order", "{}"),
                        new ToolCall(secondCallId, "get_order", "{}")
                )
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
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

    private static final class SequenceModelClient
            implements ModelClient {
        private final Queue<ModelTurn> turns = new ArrayDeque<>();
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
            return turns.remove();
        }
    }

    private static final class RecordingToolExecutor
            implements ToolExecutor {
        private final List<String> callIds = new ArrayList<>();

        @Override
        public List<ToolDefinition> definitions() {
            return List.of(new ToolDefinition(
                    "get_order",
                    "query order",
                    "{\"type\":\"object\"}",
                    true
            ));
        }

        @Override
        public ToolResult execute(RunContext context, ToolCall call) {
            callIds.add(call.callId());
            return new ToolResult(call.callId(), "ok");
        }
    }
}
