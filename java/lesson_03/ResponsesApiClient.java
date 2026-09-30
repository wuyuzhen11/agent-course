import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

final class ResponsesApiClient extends AbstractModelApiClient {
    ResponsesApiClient(ClientConfig config) {
        super(config);
    }

    @Override
    String endpoint() {
        return config.baseUrl() + "/responses";
    }

    ObjectNode toResponsesTool(ToolDefinition definition) {
        try {
            JsonNode schema = json.readTree(definition.parametersJson());
            if (!schema.isObject()) {
                throw new IllegalArgumentException("parameters Schema 必须是 JSON 对象");
            }

            ObjectNode tool = json.createObjectNode();
            tool.put("type", "function");
            tool.put("name", definition.name());
            tool.put("description", definition.description());
            tool.set("parameters", schema);
            tool.put("strict", definition.strict());
            return tool;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException(
                    "工具 parameters Schema 配置无效",
                    error
            );
        }
    }

    @Override
    ModelTurn parseResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Responses 响应为空");
        }

        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Responses 响应必须是 JSON 对象");
            }

            JsonNode output = root.get("output");
            if (output == null || !output.isArray()) {
                throw new IllegalArgumentException("Responses 响应缺少 output 数组");
            }

            List<ToolCall> toolCalls = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            List<String> rawOutputItems = new ArrayList<>();
            for (JsonNode item : output) {
                rawOutputItems.add(item.toString());
                String type = item.path("type").asText();

                if ("function_call".equals(type)) {
                    toolCalls.add(new ToolCall(
                            requiredText(item, "call_id"),
                            requiredText(item, "name"),
                            requiredText(item, "arguments")
                    ));
                    continue;
                }

                if ("message".equals(type)) {
                    JsonNode content = item.get("content");

                    if (content == null || !content.isArray()) {
                        throw new IllegalArgumentException(
                                "Responses message 缺少 content 数组"
                        );
                    }

                    for (JsonNode part : content) {
                        if ("output_text".equals(part.path("type").asText())) {
                            texts.add(requiredText(part, "text"));
                        }
                    }
                }
            }
            String text = texts.isEmpty()
                    ? null
                    : String.join("\n", texts);
            return new ModelTurn(text, toolCalls, rawOutputItems);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException(
                    "Responses 响应不是合法 JSON",
                    error
            );
        }
    }

    private String requiredText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);

        if (value == null
                || !value.isTextual()
                || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "Responses 响应缺少文本字段：" + fieldName
            );
        }

        return value.asText();
    }

    ArrayNode toResponsesInput(List<Message> history) {
        ArrayNode input = json.createArrayNode();

        for (Message message : history) {
            if (message instanceof UserMessage user) {
                ObjectNode item = input.addObject();
                item.put("role", "user");
                item.put("content", user.text());
                continue;
            }

            if (message instanceof ModelTurn turn) {
                if (!turn.rawOutputItems().isEmpty()) {
                    for (String rawOutputItem : turn.rawOutputItems()) {
                        input.add(parseRawOutputItem(rawOutputItem));
                    }
                    continue;
                }

                if (turn.text() != null && !turn.text().isBlank()) {
                    ObjectNode item = input.addObject();
                    item.put("role", "assistant");
                    item.put("content", turn.text());
                }

                for (ToolCall call : turn.toolCalls()) {
                    ObjectNode item = input.addObject();
                    item.put("type", "function_call");
                    item.put("call_id", call.callId());
                    item.put("name", call.name());
                    item.put("arguments", call.arguments());
                }
                continue;
            }

            if (message instanceof ToolResult result) {
                ObjectNode item = input.addObject();
                item.put("type", "function_call_output");
                item.put("call_id", result.callId());
                item.put("output", result.output());
                continue;
            }

            throw new IllegalArgumentException(
                    "不支持的历史消息类型：" + message.getClass().getName()
            );
        }

        return input;
    }

    private JsonNode parseRawOutputItem(String rawOutputItem) {
        try {
            JsonNode item = json.readTree(rawOutputItem);
            if (item == null || !item.isObject()) {
                throw new IllegalArgumentException(
                        "Responses 原始输出项必须是 JSON 对象"
                );
            }
            return item;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException(
                    "Responses 原始输出项不是合法 JSON",
                    error
            );
        }
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
        root.set("input", toResponsesInput(history));

        ArrayNode tools = json.createArrayNode();
        for (ToolDefinition definition : toolDefinitions) {
            tools.add(toResponsesTool(definition));
        }
        root.set("tools", tools);

        return root.toString();
    }
}
