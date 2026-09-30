package com.example.agent.lesson02;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

public final class ResponsesApiClient extends AbstractModelApiClient {
    public ResponsesApiClient(ClientConfig config) {
        super(config);
    }

    ResponsesApiClient(ClientConfig config, HttpClient http) {
        super(config, http);
    }

    @Override
    public String endpoint() {
        return config.baseUrl() + "/responses";
    }

    @Override
    public String buildRequestBody(String question) {
        ObjectNode root = json.createObjectNode();
        root.put("model", config.model());
        root.put("input", question);
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

            List<String> texts = new ArrayList<>();
            for (JsonNode outputItem : root.path("output")) {
                for (JsonNode content : outputItem.path("content")) {
                    if ("output_text".equals(content.path("type").asText())) {
                        texts.add(content.path("text").asText());
                    }
                }
            }
            String text = String.join("\n", texts);
            if (text.isBlank()) {
                throw new ModelApiException("模型响应中没有 output_text。", 200, body);
            }

            return new ModelResponse(
                    root.path("id").asText(""),
                    root.path("status").asText("completed"),
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
