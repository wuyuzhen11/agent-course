import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class AgentLoop {
    private final ModelClient model;
    private final ToolExecutor tools;
    private final RunLimits limits;

    AgentLoop(
            ModelClient model,
            ToolExecutor tools,
            RunLimits limits
    ) {
        this.model = model;
        this.tools = tools;
        this.limits = limits;
    }

    String run(String question) {
        RunContext context = RunContext.start(
                UUID.randomUUID().toString(),
                question
        );

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
                    return turn.text();
                }

                throw new IllegalStateException(
                        "模型返回了空响应"
                );
            }
        } catch (RuntimeException error) {
            context.fail();
            throw error;
        }
    }
}
