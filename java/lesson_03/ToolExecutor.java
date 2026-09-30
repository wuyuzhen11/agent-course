import java.util.List;

interface ToolExecutor {
    List<ToolDefinition> definitions();

    ToolResult execute(
            RunContext context,
            ToolCall call
    );
}