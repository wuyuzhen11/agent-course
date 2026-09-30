import java.util.List;
import java.util.Map;

// 本节使用简化的内存消息契约，每轮模型回复包含一个工具调用或一个最终回答。
interface Message {}

record UserMessage(String text) implements Message {}

interface ModelReply extends Message {}

record ToolCall(String callId, String name, Map<String, String> arguments)
        implements ModelReply {
    ToolCall {
        arguments = Map.copyOf(arguments);
    }
}

record FinalAnswer(String text) implements ModelReply {}

record ToolResult(String callId, String content) implements Message {}

record ToolDefinition(String name, String description, String requiredParameter) {}

interface ModelClient {
    ModelReply next(List<Message> history, List<ToolDefinition> tools);
}
