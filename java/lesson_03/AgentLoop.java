import java.util.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class AgentLoop {

    private static final String LIMIT_FALLBACK_MESSAGE =
            "本次查询暂时无法完成，请稍后重试；若问题持续，请联系人工客服。";
    private static final SummaryPolicy SUMMARY_DISABLED =
            new SummaryPolicy(Integer.MAX_VALUE, Integer.MAX_VALUE);
    private final ModelClient model;
    private final ToolExecutor tools;
    private final RunLimits limits;
    private final HistoryWindow historyWindow;
    private final int maxTurns;
    private final SummaryPolicy summaryPolicy;
    private final HistorySummarizer historySummarizer;
    private final SystemMessage systemMessage;

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
        this(
                model,
                tools,
                limits,
                Integer.MAX_VALUE,
                SUMMARY_DISABLED,
                new HistorySummarizer(model),
                null
        );
    }

    AgentLoop(
            ModelClient model,
            ToolExecutor tools,
            RunLimits limits,
            int maxTurns
    ) {
        this(
                model,
                tools,
                limits,
                maxTurns,
                SUMMARY_DISABLED,
                new HistorySummarizer(model),
                null
        );
    }

    AgentLoop(
            ModelClient model,
            ToolExecutor tools,
            RunLimits limits,
            SummaryPolicy summaryPolicy
    ) {
        this(
                model,
                tools,
                limits,
                Integer.MAX_VALUE,
                summaryPolicy,
                new HistorySummarizer(model),
                null
        );
    }

    AgentLoop(
            ModelClient model,
            ToolExecutor tools,
            RunLimits limits,
            SystemMessage systemMessage
    ) {
        this(
                model,
                tools,
                limits,
                Integer.MAX_VALUE,
                SUMMARY_DISABLED,
                new HistorySummarizer(model, systemMessage),
                systemMessage
        );
    }

    AgentLoop(
            ModelClient model,
            ToolExecutor tools,
            RunLimits limits,
            int maxTurns,
            SummaryPolicy summaryPolicy
    ) {
        this(
                model,
                tools,
                limits,
                maxTurns,
                summaryPolicy,
                new HistorySummarizer(model),
                null
        );
    }

    AgentLoop(
            ModelClient model,
            ToolExecutor tools,
            RunLimits limits,
            SummaryPolicy summaryPolicy,
            SystemMessage systemMessage
    ) {
        this(
                model,
                tools,
                limits,
                Integer.MAX_VALUE,
                summaryPolicy,
                new HistorySummarizer(model, systemMessage),
                systemMessage
        );
    }

    AgentLoop(
            ModelClient model,
            ToolExecutor tools,
            RunLimits limits,
            int maxTurns,
            SummaryPolicy summaryPolicy,
            SystemMessage systemMessage
    ) {
        this(
                model,
                tools,
                limits,
                maxTurns,
                summaryPolicy,
                new HistorySummarizer(model, systemMessage),
                systemMessage
        );
    }

    AgentLoop(
            ModelClient model,
            ToolExecutor tools,
            RunLimits limits,
            int maxTurns,
            SummaryPolicy summaryPolicy,
            HistorySummarizer historySummarizer
    ) {
        this(
                model,
                tools,
                limits,
                maxTurns,
                summaryPolicy,
                historySummarizer,
                null
        );
    }

    AgentLoop(
            ModelClient model,
            ToolExecutor tools,
            RunLimits limits,
            int maxTurns,
            SummaryPolicy summaryPolicy,
            HistorySummarizer historySummarizer,
            SystemMessage systemMessage
    ) {
        this.model = Objects.requireNonNull(model, "model 不能为空");
        this.tools = Objects.requireNonNull(tools, "tools 不能为空");
        this.limits = Objects.requireNonNull(limits, "limits 不能为空");
        this.maxTurns = maxTurns;
        this.historyWindow = new HistoryWindow();
        this.summaryPolicy = Objects.requireNonNull(
                summaryPolicy,
                "summaryPolicy 不能为空"
        );
        this.historySummarizer = Objects.requireNonNull(
                historySummarizer,
                "historySummarizer 不能为空"
        );
        this.systemMessage = systemMessage;
    }

    AgentRunResult run(String question) {
        return run(
                new AgentSession(UUID.randomUUID().toString()),
                question,
                AccessContext.defaultUser()
        );
    }

    AgentRunResult run(AgentSession session, String question) {
        return run(session, question, AccessContext.defaultUser());
    }

    AgentRunResult run(
            AgentSession session,
            String question,
            AccessContext accessContext
    ) {
        if (session == null) {
            throw new NullPointerException("session 不能为空");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        Objects.requireNonNull(accessContext, "accessContext 不能为空");
        List<Message> savedHistory = session.history();
        HistorySummary summary = session.summary();
        RunContext context = RunContext.start(
                session.sessionId(),
                accessContext,
                savedHistory,
                question
        );
        Set<ToolCallKey> seenToolCalls = new HashSet<>();

        try {
            OptionalInt targetEndTurn = summaryPolicy.targetEndTurn(
                    savedHistory,
                    summary
            );
            if (targetEndTurn.isPresent()) {
                context.reserveModelCall(limits);
                summary = summary == null
                        ? historySummarizer.summarize(
                                savedHistory,
                                1,
                                targetEndTurn.getAsInt()
                        )
                        : historySummarizer.summarize(
                                savedHistory,
                                summary,
                                targetEndTurn.getAsInt()
                        );
                session.updateSummary(summary);
            }

            int size = savedHistory.size();
            List<Message> selectedHistory;
            if (summary == null) {
                selectedHistory = historyWindow.select(
                        savedHistory,
                        maxTurns
                );
            } else {
                selectedHistory = historyWindow.selectAfterTurn(
                        savedHistory,
                        summary.endTurn(),
                        maxTurns
                );
            }

            while (true) {
                List<Message> inputHistory = new ArrayList<>();
                List<Message> contextHistory = context.history();
                if (systemMessage != null) {
                    inputHistory.add(systemMessage);
                }
                if (summary != null) {
                    inputHistory.add(summary.toInputMessage());
                }
                inputHistory.addAll(selectedHistory);
                inputHistory.addAll(contextHistory.subList(size, contextHistory.size()));
                context.reserveModelCall(limits);
                ModelTurn turn = model.next(
                        inputHistory,
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
                    AgentRunResult result = new AgentRunResult(
                            turn.text(),
                            context.status(),
                            context.modelCalls(),
                            context.toolCalls(),
                            context.history()
                    );
                    session.commit(result);
                    return result;
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
