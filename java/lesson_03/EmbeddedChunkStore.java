import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class EmbeddedChunkStore {
    private static final int SCHEMA_VERSION = 1;

    private final Path file;
    private final ObjectMapper json = new ObjectMapper();

    EmbeddedChunkStore(Path file) {
        this.file = Objects.requireNonNull(file, "file 不能为 null")
                .toAbsolutePath()
                .normalize();
    }

    void save(int dimensions, List<EmbeddedChunk> chunks) {
        validateDimensions(dimensions);
        chunks = List.copyOf(Objects.requireNonNull(
                chunks,
                "chunks 不能为 null"
        ));

        Path directory = file.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "vectors-", ".tmp");
            Files.writeString(
                    temporary,
                    encode(dimensions, chunks),
                    StandardCharsets.UTF_8
            );
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (IOException error) {
            throw new UncheckedIOException("向量文件保存失败：" + file, error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 正式文件的保存结果优先，临时文件留待后续清理。
                }
            }
        }
    }

    List<EmbeddedChunk> load(int expectedDimensions) {
        validateDimensions(expectedDimensions);
        if (Files.notExists(file)) {
            return List.of();
        }

        try {
            JsonNode root = json.readTree(
                    Files.readString(file, StandardCharsets.UTF_8)
            );
            return decode(root, expectedDimensions);
        } catch (JsonProcessingException error) {
            throw new EmbeddedChunkPersistenceException(
                    "向量 JSON 无效：" + file,
                    error
            );
        } catch (IOException error) {
            throw new UncheckedIOException("向量文件读取失败：" + file, error);
        }
    }

    private String encode(int dimensions, List<EmbeddedChunk> chunks) {
        ObjectNode root = json.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("dimensions", dimensions);

        ArrayNode items = root.putArray("chunks");
        for (EmbeddedChunk embeddedChunk : chunks) {
            DocumentChunk chunk = embeddedChunk.chunk();
            double[] vector = embeddedChunk.vector();
            validateVector(vector, dimensions);

            ObjectNode item = items.addObject();
            item.put("index", chunk.index());
            item.put("heading", chunk.heading());
            item.put("text", chunk.text());

            ArrayNode values = item.putArray("vector");
            for (double value : vector) {
                values.add(value);
            }
        }

        return root.toPrettyString() + System.lineSeparator();
    }

    private List<EmbeddedChunk> decode(
            JsonNode root,
            int expectedDimensions
    ) {
        if (root == null || !root.isObject()
                || !root.path("schemaVersion").isInt()
                || root.path("schemaVersion").intValue() != SCHEMA_VERSION
                || !root.path("dimensions").isIntegralNumber()
                || !root.path("dimensions").canConvertToInt()
                || root.path("dimensions").intValue() < 1
                || !root.path("chunks").isArray()) {
            throw new EmbeddedChunkPersistenceException(
                    "向量存档结构无效：" + file
            );
        }

        int storedDimensions = root.path("dimensions").intValue();
        if (storedDimensions != expectedDimensions) {
            throw new EmbeddedChunkPersistenceException(
                    "向量维度不匹配：文件为 "
                            + storedDimensions
                            + "，当前客户端为 "
                            + expectedDimensions
            );
        }

        List<EmbeddedChunk> chunks = new ArrayList<>();
        for (JsonNode item : root.path("chunks")) {
            chunks.add(decodeChunk(item, storedDimensions));
        }
        return List.copyOf(chunks);
    }

    private EmbeddedChunk decodeChunk(JsonNode item, int dimensions) {
        JsonNode indexNode = item.path("index");
        JsonNode vectorNode = item.path("vector");
        if (!item.isObject()
                || !indexNode.isIntegralNumber()
                || !indexNode.canConvertToInt()
                || indexNode.intValue() < 0
                || !vectorNode.isArray()) {
            throw new EmbeddedChunkPersistenceException(
                    "向量块结构无效：" + file
            );
        }

        DocumentChunk chunk = new DocumentChunk(
                indexNode.intValue(),
                requiredText(item, "heading"),
                requiredText(item, "text")
        );

        if (vectorNode.size() != dimensions) {
            throw new EmbeddedChunkPersistenceException(
                    "向量长度与文件维度不一致：" + file
            );
        }

        double[] vector = new double[dimensions];
        for (int i = 0; i < dimensions; i++) {
            JsonNode value = vectorNode.get(i);
            if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                throw new EmbeddedChunkPersistenceException(
                        "向量包含无效数值：" + file
                );
            }
            vector[i] = value.doubleValue();
        }
        return new EmbeddedChunk(chunk, vector);
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isTextual()) {
            throw new EmbeddedChunkPersistenceException(
                    "向量块字段无效：" + field
            );
        }
        return value.textValue();
    }

    private static void validateDimensions(int dimensions) {
        if (dimensions < 1) {
            throw new IllegalArgumentException("dimensions 至少为 1");
        }
    }

    private static void validateVector(double[] vector, int dimensions) {
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                    "vector 长度必须等于 dimensions"
            );
        }
        for (double value : vector) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("vector 必须包含有限数值");
            }
        }
    }
}

final class EmbeddedChunkPersistenceException extends RuntimeException {
    EmbeddedChunkPersistenceException(String message) {
        super(message);
    }

    EmbeddedChunkPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
