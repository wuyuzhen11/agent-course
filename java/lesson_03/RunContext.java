import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class RunContext {
    private final String sessionId;
    private final List<Message> history = new ArrayList<>();

    private int modelCalls;
    private int toolCalls;
    private RunStatus status;

    private RunContext(String sessionId, List<Message> previousHistory, String question) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }

        this.sessionId = sessionId;
        this.history.addAll(Objects.requireNonNull(previousHistory, "previousHistory 不能为空"));
        this.history.add(new UserMessage(question));
        this.status = RunStatus.RUNNING;
    }

    static RunContext start(String sessionId, String question) {
        return start(sessionId, List.of(), question);
    }

    static RunContext start(String sessionId, List<Message> previousHistory, String question) {
        return new RunContext(sessionId, previousHistory, question);
    }

    String sessionId() {
        return sessionId;
    }

    List<Message> history() {
        return List.copyOf(history);
    }

    int modelCalls() {
        return modelCalls;
    }

    int toolCalls() {
        return toolCalls;
    }

    RunStatus status() {
        return status;
    }

    void append(Message message) {
        history.add(Objects.requireNonNull(message));
    }

    void reserveModelCall(RunLimits limits) {
        if (modelCalls + 1 > limits.maxModelCalls()) {
            throw new AgentLimitExceededException(
                    "模型调用次数超过上限"
            );
        }

        modelCalls++;
    }

    void reserveToolCalls(int count, RunLimits limits) {
        if (count < 1) {
            throw new IllegalArgumentException(
                    "工具调用数量至少为 1"
            );
        }

        if (toolCalls + count > limits.maxToolCalls()) {
            throw new AgentLimitExceededException(
                    "工具调用次数超过上限"
            );
        }

        toolCalls += count;
    }

    void pause() {
        status = RunStatus.PAUSED;
    }

    void succeed() {
        status = RunStatus.SUCCEEDED;
    }

    void fail() {
        status = RunStatus.FAILED;
    }
}
