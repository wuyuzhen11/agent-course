import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * 根据未摘要历史的长度决定是否生成新的会话摘要。
 *
 * @param maxUnsummarizedTurns 允许保留的未摘要轮数
 * @param recentTurnsToKeep 生成摘要后继续保留的最近原始轮数
 */
record SummaryPolicy(
        int maxUnsummarizedTurns,
        int recentTurnsToKeep
) {
    SummaryPolicy {
        if (maxUnsummarizedTurns < 1) {
            throw new IllegalArgumentException(
                    "maxUnsummarizedTurns 至少为 1"
            );
        }
        if (recentTurnsToKeep < 0) {
            throw new IllegalArgumentException(
                    "recentTurnsToKeep 至少为 0"
            );
        }
        if (recentTurnsToKeep > maxUnsummarizedTurns) {
            throw new IllegalArgumentException(
                    "recentTurnsToKeep 必须小于或等于 maxUnsummarizedTurns"
            );
        }
    }

    /**
     * 根据完整历史计算本次摘要的结束轮次。
     *
     * @return 为空表示当前仍在阈值内，否则返回摘要应覆盖的最后一轮
     */
    OptionalInt targetEndTurn(
            List<Message> history,
            HistorySummary currentSummary
    ) {
        Objects.requireNonNull(history, "history 不能为空");

        // 每条 UserMessage 代表一轮，工具消息和模型消息属于该轮内容。
        int totalTurns = (int) history.stream()
                .filter(message -> message instanceof UserMessage)
                .count();

        // 没有历史摘要时，从第 1 轮开始计算未摘要范围。
        int summarizedEndTurn = currentSummary == null
                ? 0
                : currentSummary.endTurn();

        if (summarizedEndTurn > totalTurns) {
            throw new IllegalArgumentException(
                    "摘要结束轮次必须位于当前历史范围内"
            );
        }

        int unsummarizedTurns = totalTurns - summarizedEndTurn;
        if (unsummarizedTurns <= maxUnsummarizedTurns) {
            return OptionalInt.empty();
        }

        // 先保留最近的原始轮次，摘要覆盖更早的完整轮次。
        return OptionalInt.of(totalTurns - recentTurnsToKeep);
    }
}
