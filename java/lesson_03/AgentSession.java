import java.util.List;
import java.util.Objects;

final class AgentSession {
    private final String sessionId;
    private List<Message> history;
    private SessionFileStore sessionFileStore;
    private long revision;

    AgentSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }

        this.sessionId = sessionId;
        this.history = List.of();
    }

    String sessionId() {
        return sessionId;
    }

    List<Message> history() {
        return List.copyOf(history);
    }

    void commit(AgentRunResult result) {
        Objects.requireNonNull(result, "result 不能为空");
        if (result.status() != RunStatus.SUCCEEDED) {
            throw new IllegalArgumentException("只有成功完成的运行结果可以提交到会话");
        }
        List<Message> lingshihistory = List.copyOf(result.history());
        if (sessionFileStore != null) {
            revision = sessionFileStore.save(sessionId, revision, lingshihistory);
        }
        history = List.copyOf(result.history());
    }

    static AgentSession open(String sessionId, SessionFileStore store) {
        SessionFileStore.StoredSession load = store.load(sessionId);
        AgentSession agentSession = new AgentSession(sessionId);
        agentSession.revision = load.revision();
        agentSession.history = load.history();
        agentSession.sessionFileStore = store;
        return agentSession;
    }
}
