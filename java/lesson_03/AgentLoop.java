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

                    for (ToolCall call : turn.toolCalls()) {
                        ToolResult result = tools.execute(
                                context,
                                call
                        );
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
