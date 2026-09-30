import java.util.List;

/**
 * Agent 历史中的统一消息类型。
 *
 * UserMessage、ModelTurn 和 ToolResult 是历史元素；
 * ToolCall 是 ModelTurn 内部的单个工具调用请求。
 */
sealed interface Message
        permits UserMessage, ModelTurn, ToolResult {
}

record UserMessage(String text) implements Message {
}

/** 模型请求 Java 执行一个工具。 */
record ToolCall(
        String callId,
        String name,
        String arguments
) {
}

/** 模型的一次完整响应，可以同时包含文本和多个工具调用。 */
record ModelTurn(
        String text,
        List<ToolCall> toolCalls,
        List<String> rawOutputItems
) implements Message {
    ModelTurn(String text, List<ToolCall> toolCalls) {
        this(text, toolCalls, List.of());
    }

    ModelTurn {
        toolCalls = List.copyOf(toolCalls == null ? List.of() : toolCalls);
        rawOutputItems = List.copyOf(
                rawOutputItems == null ? List.of() : rawOutputItems
        );
    }
}

/** Java 执行工具后回填给模型的结果。 */
record ToolResult(
        String callId,
        String output
) implements Message {
}
