import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class AgentLoop {

    private static final String LIMIT_FALLBACK_MESSAGE =
            "本次查询暂时无法完成，请稍后重试；若问题持续，请联系人工客服。";
    private final ModelClient model;
    private final ToolExecutor tools;
    private final RunLimits limits;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private record ToolCallKey(
            String name,
            boolean validJson,
            JsonNode arguments
    ) {
    }

    AgentLoop(
            ModelClient model,
            ToolExecutor tools,
            RunLimits limits
    ) {
        this.model = model;
        this.tools = tools;
        this.limits = limits;
    }

    AgentRunResult run(String question) {
        RunContext context = RunContext.start(
                UUID.randomUUID().toString(),
                question
        );
        Set<ToolCallKey> seenToolCalls = new HashSet<>();

        try {
            while (true) {
                context.reserveModelCall(limits);

                ModelTurn turn = model.next(
                        context.history(),
                        tools.definitions()
                );

                context.append(turn);

                if (!turn.toolCalls().isEmpty()) {
                    if (turn.toolCalls().size()
                            > limits.maxToolsPerTurn()) {
                        throw new AgentLimitExceededException(
                                "单轮工具调用数量超过上限"
                        );
                    }

                    context.reserveToolCalls(
                            turn.toolCalls().size(),
                            limits
                    );

                    Set<String> callIds = new HashSet<>();
                    for (ToolCall call : turn.toolCalls()) {
                        if (call.callId() == null
                                || call.callId().isBlank()
                                || !callIds.add(call.callId())) {
                            throw new IllegalStateException(
                                    "工具调用 callId 缺失或重复"
                            );
                        }
                    }

                    for (ToolCall call : turn.toolCalls()) {
                        ToolCallKey key = keyOf(call);

                        if (!seenToolCalls.add(key)) {
                            context.append(new ToolResult(
                                    call.callId(),
                                    """
                                    {"code":"REPEATED_TOOL_CALL","message":"相同工具和参数已经调用过，请使用已有结果或调整参数"}
                                    """
                            ));
                            continue;
                        }
                        ToolResult result = tools.execute(
                                context,
                                call
                        );

                        if (result == null
                                || !call.callId().equals(result.callId())) {
                            throw new IllegalStateException(
                                    "工具结果 callId 与工具调用不匹配"
                            );
                        }

                        context.append(result);
                    }

                    continue;
                }

                if (turn.text() != null
                        && !turn.text().isBlank()) {
                    context.succeed();
                    return new AgentRunResult(
                            turn.text(),
                            context.status(),
                            context.modelCalls(),
                            context.toolCalls(),
                            context.history()
                    );
                }

                throw new IllegalStateException(
                        "模型返回了空响应"
                );
            }
        } catch (AgentLimitExceededException error) {
            context.fail();

            return new AgentRunResult(
                    LIMIT_FALLBACK_MESSAGE,
                    context.status(),
                    context.modelCalls(),
                    context.toolCalls(),
                    context.history()
            );
        } catch (RuntimeException error) {
            context.fail();
            throw error;
        }
    }

    private ToolCallKey keyOf(ToolCall call) {
        String rawArguments = call.arguments();

        if (rawArguments == null || rawArguments.isBlank()) {
            JsonNode rawNode = objectMapper.getNodeFactory()
                    .textNode(rawArguments == null ? "" : rawArguments.trim());
            return new ToolCallKey(call.name(), false, rawNode);
        }

        try {
            return new ToolCallKey(
                    call.name(),
                    true,
                    objectMapper.readTree(rawArguments)
            );
        } catch (JsonProcessingException error) {
            JsonNode rawNode = objectMapper.getNodeFactory()
                    .textNode(rawArguments.trim());
            return new ToolCallKey(call.name(), false, rawNode);
        }
    }
}
