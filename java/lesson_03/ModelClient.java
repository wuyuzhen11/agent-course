import java.util.List;

interface ModelClient {
    ModelTurn next(
            List<Message> history,
            List<ToolDefinition> toolDefinitions
    );
}