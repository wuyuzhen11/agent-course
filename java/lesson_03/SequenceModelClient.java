import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

final class SequenceModelClient implements ModelClient {
    private final Queue<ModelTurn> turns = new ArrayDeque<>();

    SequenceModelClient(ModelTurn... turns) {
        this.turns.addAll(List.of(turns));
    }

    @Override
    public ModelTurn next(
            List<Message> history,
            List<ToolDefinition> toolDefinitions
    ) {

        ModelTurn turn = turns.poll();

        if (turn == null) {
            throw new IllegalStateException("预设模型响应已耗尽");
        }

        return turn;

    }
}