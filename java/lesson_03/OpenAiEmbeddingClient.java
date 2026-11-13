import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class OpenAiEmbeddingClient implements EmbeddingClient {
    private final EmbeddingConfig config;
    private final HttpTransport transport;
    private final ObjectMapper json;

    OpenAiEmbeddingClient(EmbeddingConfig config) {
        this(config, new JdkHttpTransport());
    }

    OpenAiEmbeddingClient(
            EmbeddingConfig config,
            HttpTransport transport
    ) {
        this.config = Objects.requireNonNull(config, "config 不能为 null");
        this.transport = Objects.requireNonNull(
                transport,
                "transport 不能为 null"
        );
        this.json = new ObjectMapper();
    }

    @Override
    public int dimensions() {
        return config.dimensions();
    }

    @Override
    public List<double[]> embed(List<String> texts) {
        texts = validateTexts(texts);
        if (texts.isEmpty()) {
            return List.of();
        }
        config.requireApiKey();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/embeddings"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        buildRequestBody(texts),
                        StandardCharsets.UTF_8
                ))
                .build();

        HttpResponse<String> response = send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ModelApiException(
                    "Embedding 服务返回 HTTP " + response.statusCode(),
                    response.statusCode(),
                    response.body()
            );
        }
        return parseResponse(response.body(), texts.size());
    }

    private List<String> validateTexts(List<String> texts) {
        Objects.requireNonNull(texts, "texts 不能为 null");
        List<String> validated = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("文本必须包含有效内容");
            }
            validated.add(text);
        }
        return List.copyOf(validated);
    }

    private String buildRequestBody(List<String> texts) {
        ObjectNode root = json.createObjectNode();
        root.put("model", config.model());
        root.put("encoding_format", "float");
        root.put("dimensions", config.dimensions());

        ArrayNode input = root.putArray("input");
        texts.forEach(input::add);
        return root.toString();
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return transport.send(request);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelApiException(
                    "Embedding 调用被中断",
                    0,
                    "",
                    error
            );
        } catch (IOException error) {
            throw new ModelApiException(
                    "Embedding 服务连接失败：" + error.getMessage(),
                    0,
                    "",
                    error
            );
        }
    }

    private List<double[]> parseResponse(String body, int expectedCount) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode data = root == null ? null : root.get("data");
            if (data == null || !data.isArray()
                    || data.size() != expectedCount) {
                throw invalidResponse(body, "data 数量与输入数量不一致");
            }

            double[][] ordered = new double[expectedCount][];
            for (JsonNode item : data) {
                int index = responseIndex(item, expectedCount, body);
                if (ordered[index] != null) {
                    throw invalidResponse(body, "data 包含重复 index");
                }
                ordered[index] = responseVector(item, body);
            }

            List<double[]> vectors = new ArrayList<>(expectedCount);
            for (double[] vector : ordered) {
                if (vector == null) {
                    throw invalidResponse(body, "data 缺少输入对应的 index");
                }
                vectors.add(vector);
            }
            return List.copyOf(vectors);
        } catch (ModelApiException error) {
            throw error;
        } catch (JsonProcessingException error) {
            throw new ModelApiException(
                    "Embedding 响应 JSON 无效",
                    200,
                    body,
                    error
            );
        } catch (RuntimeException error) {
            throw new ModelApiException(
                    "Embedding 响应解析失败：" + error.getMessage(),
                    200,
                    body,
                    error
            );
        }
    }

    private int responseIndex(
            JsonNode item,
            int expectedCount,
            String body
    ) {
        JsonNode index = item.get("index");
        if (!item.isObject() || index == null
                || !index.isIntegralNumber()
                || !index.canConvertToInt()
                || index.intValue() < 0
                || index.intValue() >= expectedCount) {
            throw invalidResponse(body, "data.index 无效");
        }
        return index.intValue();
    }

    private double[] responseVector(JsonNode item, String body) {
        JsonNode embedding = item.get("embedding");
        if (embedding == null || !embedding.isArray()
                || embedding.size() != config.dimensions()) {
            throw invalidResponse(body, "embedding 维度错误");
        }

        double[] vector = new double[config.dimensions()];
        for (int i = 0; i < vector.length; i++) {
            JsonNode value = embedding.get(i);
            if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                throw invalidResponse(body, "embedding 包含无效数值");
            }
            vector[i] = value.doubleValue();
        }
        return vector;
    }

    private ModelApiException invalidResponse(String body, String reason) {
        return new ModelApiException(
                "Embedding 响应结构无效：" + reason,
                200,
                body
        );
    }
}
