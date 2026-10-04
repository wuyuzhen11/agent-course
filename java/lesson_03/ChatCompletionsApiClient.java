import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

final class ChatCompletionsApiClient extends AbstractModelApiClient {
    ChatCompletionsApiClient(
            ClientConfig config,
            RetryPolicy retryPolicy
    ) {
        super(config, retryPolicy);
    }

    ChatCompletionsApiClient(
            ClientConfig config,
            HttpClient httpClient,
            RetryPolicy retryPolicy
    ) {
        super(config,httpClient, retryPolicy);
    }

    @Override
    String endpoint() {
        return config.baseUrl() + "/chat/completions";
    }

    ArrayNode toChatMessages(List<Message> history) {
        ArrayNode messages = json.createArrayNode();

        for (Message message : history) {
            if (message instanceof UserMessage user) {
                ObjectNode item = messages.addObject();
                item.put("role", "user");
                item.put("content", user.text());
                continue;
            }

            if (message instanceof ModelTurn turn) {
                ObjectNode item = messages.addObject();
                item.put("role", "assistant");

                if (turn.text() == null) {
                    item.putNull("content");
                } else {
                    item.put("content", turn.text());
                }

                if (!turn.toolCalls().isEmpty()) {
                    ArrayNode toolCalls = item.putArray("tool_calls");

                    for (ToolCall call : turn.toolCalls()) {
                        ObjectNode rawCall = toolCalls.addObject();
                        rawCall.put("id", call.callId());
                        rawCall.put("type", "function");

                        ObjectNode function =
                                rawCall.putObject("function");
                        function.put("name", call.name());
                        function.put("arguments", call.arguments());
                    }
                }
                continue;
            }

            if (message instanceof ToolResult result) {
                ObjectNode item = messages.addObject();
                item.put("role", "tool");
                item.put("tool_call_id", result.callId());
                item.put("content", result.output());
                continue;
            }

            throw new IllegalArgumentException(
                    "不支持的历史消息类型："
                            + message.getClass().getName()
            );
        }

        return messages;
    }

    @Override
    String buildRequestBody(
            String model,
            List<Message> history,
            List<ToolDefinition> toolDefinitions
    ) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型名不能为空");
        }

        ObjectNode root = json.createObjectNode();
        root.put("model", model);
        root.set("messages", toChatMessages(history));

        ArrayNode tools = json.createArrayNode();
        for (ToolDefinition definition : toolDefinitions) {
            tools.add(toChatTool(definition));
        }
        root.set("tools", tools);

        return root.toString();
    }

    @Override
    ModelTurn parseResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException(
                    "Chat Completions 响应为空"
            );
        }

        try {
            JsonNode root = json.readTree(body);
            JsonNode choices = root.get("choices");

            if (choices == null
                    || !choices.isArray()
                    || choices.size() == 0) {
                throw new IllegalArgumentException(
                        "Chat Completions 响应缺少 choices"
                );
            }

            JsonNode message = choices.get(0).get("message");
            if (message == null || !message.isObject()) {
                throw new IllegalArgumentException(
                        "Chat Completions 响应缺少 message"
                );
            }

            String text = null;
            JsonNode content = message.get("content");

            if (content != null && !content.isNull()) {
                if (!content.isTextual()) {
                    throw new IllegalArgumentException(
                            "message.content 必须是字符串或 null"
                    );
                }

                if (!content.asText().isBlank()) {
                    text = content.asText();
                }
            }

            List<ToolCall> toolCalls = new ArrayList<>();
            JsonNode rawToolCalls = message.get("tool_calls");

            if (rawToolCalls != null && !rawToolCalls.isNull()) {
                if (!rawToolCalls.isArray()) {
                    throw new IllegalArgumentException(
                            "message.tool_calls 必须是数组"
                    );
                }

                for (JsonNode rawCall : rawToolCalls) {
                    if (!"function".equals(
                            rawCall.path("type").asText())) {
                        continue;
                    }

                    JsonNode function = rawCall.get("function");
                    if (function == null || !function.isObject()) {
                        throw new IllegalArgumentException(
                                "tool_call 缺少 function"
                        );
                    }

                    toolCalls.add(new ToolCall(
                            requiredText(rawCall, "id"),
                            requiredText(function, "name"),
                            requiredText(function, "arguments")
                    ));
                }
            }

            return new ModelTurn(text, toolCalls);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException(
                    "Chat Completions 响应不是合法 JSON",
                    error
            );
        }
    }

    private String requiredText(
            JsonNode node,
            String fieldName
    ) {
        JsonNode value = node.get(fieldName);

        if (value == null
                || !value.isTextual()
                || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "Chat 响应缺少文本字段：" + fieldName
            );
        }

        return value.asText();
    }

    ObjectNode toChatTool(ToolDefinition definition) {
        try {
            JsonNode schema = json.readTree(
                    definition.parametersJson()
            );

            if (!schema.isObject()) {
                throw new IllegalArgumentException(
                        "parameters Schema 必须是 JSON 对象"
                );
            }

            ObjectNode tool = json.createObjectNode();
            tool.put("type", "function");

            ObjectNode function = tool.putObject("function");
            function.put("name", definition.name());
            function.put("description", definition.description());
            function.set("parameters", schema);
            function.put("strict", definition.strict());

            return tool;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException(
                    "工具 parameters Schema 配置无效",
                    error
            );
        }
    }
}
