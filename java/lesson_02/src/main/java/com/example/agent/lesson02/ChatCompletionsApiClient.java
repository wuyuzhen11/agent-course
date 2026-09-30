package com.example.agent.lesson02;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;

public final class ChatCompletionsApiClient extends AbstractModelApiClient {
    public ChatCompletionsApiClient(ClientConfig config) {
        super(config);
    }

    ChatCompletionsApiClient(ClientConfig config, HttpClient http) {
        super(config, http);
    }

    @Override
    public String endpoint() {
        return config.baseUrl() + "/chat/completions";
    }

    @Override
    public String buildRequestBody(String question) {
        ObjectNode root = json.createObjectNode();
        root.put("model", config.model());
        ObjectNode message = root.putArray("messages").addObject();
        message.put("role", "user");
        message.put("content", question);
        return root.toString();
    }

    @Override
    protected ModelResponse parseResponse(String body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new ModelApiException(
                        "模型服务返回业务错误：" + error.path("message").asText(error.toString()),
                        200,
                        body
                );
            }

            JsonNode choice = root.path("choices").path(0);
            String text = choice.path("message").path("content").asText("");
            if (text.isBlank()) {
                throw new ModelApiException("模型响应中没有 choices[0].message.content。", 200, body);
            }

            return new ModelResponse(
                    root.path("id").asText(""),
                    choice.path("finish_reason").asText("completed"),
                    text,
                    root.path("usage").path("total_tokens").asLong(0)
            );
        } catch (ModelApiException error) {
            throw error;
        } catch (Exception error) {
            throw new ModelApiException("模型响应 JSON 解析失败。", 200, body, error);
        }
    }
}
