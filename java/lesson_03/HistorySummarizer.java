import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 使用模型把指定范围内的完整会话轮次压缩为一个持久化摘要。
 */
final class HistorySummarizer {
    private static final String SUMMARY_INSTRUCTION = """
            请将上方指定范围内的历史会话压缩成一段准确、简洁的摘要。
            保留用户目标、已确认事实、关键工具结果、未完成事项和后续对话需要的指代信息。
            只输出摘要正文，不要输出标题、解释或 Markdown 代码块，也不要调用工具。
            """;

    private final ModelClient model;
    private final SystemMessage systemMessage;

    HistorySummarizer(ModelClient model) {
        this(model, null);
    }

    HistorySummarizer(ModelClient model, SystemMessage systemMessage) {
        this.model = Objects.requireNonNull(model, "model 不能为空");
        this.systemMessage = systemMessage;
    }

    HistorySummary summarize(
            List<Message> history,
            int startTurn,
            int endTurn
    ) {
        Objects.requireNonNull(history, "history 不能为空");
        validateRange(startTurn, endTurn);

        List<Message> selectedHistory = selectTurns(
                history,
                startTurn,
                endTurn
        );
        return generateSummary(
                selectedHistory,
                startTurn,
                endTurn
        );
    }

    /**
     * 在已有摘要的基础上压缩新增轮次，保留原摘要覆盖的起始轮次。
     */
    HistorySummary summarize(
            List<Message> history,
            HistorySummary previousSummary,
            int endTurn
    ) {
        Objects.requireNonNull(history, "history 不能为空");
        Objects.requireNonNull(previousSummary, "previousSummary 不能为空");
        int startTurn = previousSummary.startTurn();
        int firstNewTurn = previousSummary.endTurn() + 1;
        if (endTurn <= previousSummary.endTurn()) {
            throw new IllegalArgumentException(
                    "新的摘要结束轮次必须晚于已有摘要"
            );
        }

        List<Message> selectedHistory = selectTurns(
                history,
                firstNewTurn,
                endTurn
        );
        List<Message> prompt = new ArrayList<>(selectedHistory.size() + 2);
        prompt.add(previousSummary.toInputMessage());
        prompt.addAll(selectedHistory);
        return generateSummary(prompt, startTurn, endTurn);
    }

    private HistorySummary generateSummary(
            List<Message> selectedHistory,
            int startTurn,
            int endTurn
    ) {
        int systemMessageCount = systemMessage == null ? 0 : 1;
        List<Message> prompt = new ArrayList<>(
                selectedHistory.size() + systemMessageCount + 1
        );
        if (systemMessage != null) {
            prompt.add(systemMessage);
        }
        prompt.addAll(selectedHistory);
        prompt.add(new UserMessage(
                SUMMARY_INSTRUCTION
                        + "摘要范围：第 "
                        + startTurn
                        + " 至 "
                        + endTurn
                        + " 轮。"
        ));

        ModelTurn response = model.next(prompt, List.of());
        if (response == null) {
            throw new IllegalStateException("摘要模型没有返回响应");
        }
        if (!response.toolCalls().isEmpty()) {
            throw new IllegalStateException("摘要模型不应调用工具");
        }
        if (response.text() == null || response.text().isBlank()) {
            throw new IllegalStateException("摘要模型没有返回有效文本");
        }

        return new HistorySummary(
                response.text().strip(),
                startTurn,
                endTurn
        );
    }

    private void validateRange(int startTurn, int endTurn) {
        if (startTurn < 1) {
            throw new IllegalArgumentException("startTurn 至少为 1");
        }
        if (endTurn < startTurn) {
            throw new IllegalArgumentException(
                    "endTurn 必须大于或等于 startTurn"
            );
        }
    }

    private List<Message> selectTurns(
            List<Message> history,
            int startTurn,
            int endTurn
    ) {
        validateRange(startTurn, endTurn);
        int totalTurns = countTurns(history);
        if (endTurn > totalTurns) {
            throw new IllegalArgumentException(
                    "摘要范围必须位于当前历史范围内"
            );
        }

        int startIndex = -1;
        int endIndex = history.size();
        int turn = 0;

        for (int i = 0; i < history.size(); i++) {
            if (!(history.get(i) instanceof UserMessage)) {
                continue;
            }

            turn++;
            if (turn == startTurn) {
                startIndex = i;
            }
            if (turn == endTurn + 1) {
                endIndex = i;
                break;
            }
        }

        if (startIndex < 0) {
            throw new IllegalArgumentException(
                    "摘要起始轮次必须位于当前历史范围内"
            );
        }

        return List.copyOf(history.subList(startIndex, endIndex));
    }

    private int countTurns(List<Message> history) {
        int turns = 0;
        for (Message message : history) {
            if (message instanceof UserMessage) {
                turns++;
            }
        }
        return turns;
    }
}
