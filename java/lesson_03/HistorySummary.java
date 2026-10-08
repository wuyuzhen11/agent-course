import java.util.Objects;

record HistorySummary(String content, int startTurn, int endTurn) {
    HistorySummary {
        Objects.requireNonNull(content, "content 必须提供摘要文本");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content 必须包含有效文本");
        }
        if (startTurn < 1) {
            throw new IllegalArgumentException("startTurn 至少为 1");
        }
        if (endTurn < startTurn) {
            throw new IllegalArgumentException("endTurn 必须大于或等于 startTurn");
        }
    }
}
