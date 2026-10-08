import java.util.List;
import java.util.Objects;

record AgentRunResult(
        String finalAnswer,
        RunStatus status,
        int modelCalls,
        int toolCalls,
        List<Message> history
) {
    AgentRunResult {
        if (finalAnswer == null || finalAnswer.isBlank()) {
            throw new IllegalArgumentException("finalAnswer 不能为空");
        }
        Objects.requireNonNull(status, "status 不能为空");
        if (modelCalls < 1) {
            throw new IllegalArgumentException("modelCalls 至少为 1");
        }
        if (toolCalls < 0) {
            throw new IllegalArgumentException("toolCalls 不能小于 0");
        }
        history = List.copyOf(Objects.requireNonNull(
                history,
                "history 不能为空"
        ));
    }
}
