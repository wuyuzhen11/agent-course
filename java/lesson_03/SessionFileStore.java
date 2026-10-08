import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class SessionFileStore {
    private static final int SCHEMA_VERSION = 1;

    private final Path directory;
    private final ObjectMapper json = new ObjectMapper();

    SessionFileStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory 不能为空")
                .toAbsolutePath()
                .normalize();
    }

    StoredSession load(String sessionId) {
        Path file = fileFor(sessionId);
        if (Files.notExists(file)) {
            return new StoredSession(0, List.of());
        }

        try {
            JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
            return decode(sessionId, root);
        } catch (JsonProcessingException error) {
            throw new SessionPersistenceException("会话 JSON 无效：" + file, error);
        } catch (IOException error) {
            throw new UncheckedIOException("会话读取失败：" + file, error);
        }
    }

    long save(String sessionId, long expectedRevision, List<Message> history) {
        Path file = fileFor(sessionId);
        validateHistory(history);
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("会话版本不能小于零");
        }

        try {
            Files.createDirectories(directory);
            Path lockPath = directory.resolve(sessionId + ".lock");
            try (FileChannel channel = FileChannel.open(
                    lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                long currentRevision = load(sessionId).revision();
                if (currentRevision != expectedRevision) {
                    throw new SessionConflictException("会话已更新，请重新加载：" + sessionId);
                }
                long nextRevision = expectedRevision + 1;
                Path temporary = Files.createTempFile(directory, sessionId + ".", ".tmp");
                try {
                    Files.writeString(
                            temporary,
                            encode(sessionId, nextRevision, history),
                            StandardCharsets.UTF_8
                    );
                    Files.move(
                            temporary,
                            file,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                } finally {
                    Files.deleteIfExists(temporary);
                }
                return nextRevision;
            }
        } catch (IOException error) {
            throw new UncheckedIOException("会话保存失败：" + file, error);
        }
    }

    private Path fileFor(String sessionId) {
        validateSessionId(sessionId);
        return directory.resolve(sessionId + ".json");
    }

    private String encode(String sessionId, long revision, List<Message> history) {
        ObjectNode root = json.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("sessionId", sessionId);
        root.put("revision", revision);

        ArrayNode items = root.putArray("history");
        for (Message message : history) {
            ObjectNode item = items.addObject();
            if (message instanceof UserMessage user) {
                item.put("kind", "user");
                item.put("text", user.text());
                continue;
            }

            if (message instanceof ModelTurn turn) {
                item.put("kind", "model");
                if (turn.text() == null) {
                    item.putNull("text");
                } else {
                    item.put("text", turn.text());
                }
                ArrayNode calls = item.putArray("toolCalls");
                for (ToolCall call : turn.toolCalls()) {
                    ObjectNode encodedCall = calls.addObject();
                    encodedCall.put("callId", call.callId());
                    encodedCall.put("name", call.name());
                    encodedCall.put("arguments", call.arguments());
                }
                ArrayNode rawItems = item.putArray("rawOutputItems");
                turn.rawOutputItems().forEach(rawItems::add);
                continue;
            }

            if (message instanceof ToolResult result) {
                item.put("kind", "tool");
                item.put("callId", result.callId());
                item.put("output", result.output());
                continue;
            }

            throw new SessionPersistenceException(
                    "不支持的会话消息类型：" + message.getClass().getName()
            );
        }
        return root.toPrettyString() + System.lineSeparator();
    }

    private StoredSession decode(String sessionId, JsonNode root) {
        if (root == null || !root.isObject()
                || !root.path("schemaVersion").isInt()
                || root.path("schemaVersion").intValue() != SCHEMA_VERSION
                || !sessionId.equals(requiredText(root, "sessionId"))
                || !root.path("revision").isIntegralNumber()
                || !root.path("revision").canConvertToLong()
                || root.path("revision").longValue() < 1
                || !root.path("history").isArray()) {
            throw new SessionPersistenceException("会话存档结构无效：" + sessionId);
        }

        List<Message> history = new ArrayList<>();
        for (JsonNode item : root.path("history")) {
            String kind = requiredText(item, "kind");
            switch (kind) {
                case "user" -> history.add(new UserMessage(requiredText(item, "text")));
                case "model" -> history.add(decodeModelTurn(item));
                case "tool" -> history.add(new ToolResult(
                        requiredText(item, "callId"),
                        textField(item, "output")
                ));
                default -> throw new SessionPersistenceException(
                        "未知会话消息类型：" + kind
                );
            }
        }
        validateHistory(history);
        return new StoredSession(root.path("revision").longValue(), List.copyOf(history));
    }

    private ModelTurn decodeModelTurn(JsonNode item) {
        JsonNode callsNode = item.path("toolCalls");
        JsonNode rawItemsNode = item.path("rawOutputItems");
        if (!callsNode.isArray() || !rawItemsNode.isArray()) {
            throw new SessionPersistenceException("模型消息数组字段无效");
        }

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : callsNode) {
            calls.add(new ToolCall(
                    requiredText(call, "callId"),
                    requiredText(call, "name"),
                    textField(call, "arguments")
            ));
        }

        List<String> rawItems = new ArrayList<>();
        for (JsonNode rawItem : rawItemsNode) {
            if (!rawItem.isTextual()) {
                throw new SessionPersistenceException("模型原始输出项必须是字符串");
            }
            rawItems.add(rawItem.textValue());
        }

        JsonNode text = item.get("text");
        if (text != null && !text.isNull() && !text.isTextual()) {
            throw new SessionPersistenceException("模型文本字段无效");
        }
        return new ModelTurn(
                text == null || text.isNull() ? null : text.textValue(),
                calls,
                rawItems
        );
    }

    private String requiredText(JsonNode node, String field) {
        String value = textField(node, field);
        if (value.isBlank()) {
            throw new SessionPersistenceException("字段无效：" + field);
        }
        return value;
    }

    private String textField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new SessionPersistenceException("字段无效：" + field);
        }
        return value.textValue();
    }

    private static void validateSessionId(String sessionId) {
        if (sessionId == null
                || !sessionId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException(
                    "会话名使用 1 到 64 个英文字母、数字、下划线或连字符"
            );
        }
    }

    private static void validateHistory(List<Message> history) {
        Objects.requireNonNull(history, "history 不能为空");
        boolean expectsUser = true;
        Set<String> pendingCallIds = new HashSet<>();

        for (Message message : history) {
            if (message instanceof UserMessage user) {
                if (!expectsUser || user.text() == null || user.text().isBlank()) {
                    throw new SessionPersistenceException("用户消息顺序无效");
                }
                expectsUser = false;
                continue;
            }

            if (message instanceof ModelTurn turn) {
                if (expectsUser || !pendingCallIds.isEmpty()) {
                    throw new SessionPersistenceException("模型消息顺序无效");
                }
                if (turn.toolCalls().isEmpty()) {
                    if (turn.text() == null || turn.text().isBlank()) {
                        throw new SessionPersistenceException("模型回答不能为空");
                    }
                    expectsUser = true;
                    continue;
                }
                for (ToolCall call : turn.toolCalls()) {
                    if (call.callId() == null || call.callId().isBlank()
                            || call.name() == null || call.name().isBlank()
                            || call.arguments() == null
                            || !pendingCallIds.add(call.callId())) {
                        throw new SessionPersistenceException("工具调用字段无效");
                    }
                }
                continue;
            }

            if (message instanceof ToolResult result) {
                if (result.callId() == null || result.output() == null
                        || !pendingCallIds.remove(result.callId())) {
                    throw new SessionPersistenceException("工具结果无法关联调用");
                }
                continue;
            }

            throw new SessionPersistenceException("未知会话消息类型");
        }

        if (!expectsUser || !pendingCallIds.isEmpty()) {
            throw new SessionPersistenceException("会话末尾存在未完成轮次");
        }
    }

    record StoredSession(long revision, List<Message> history) {
        StoredSession {
            history = List.copyOf(history);
        }
    }
}

final class SessionPersistenceException extends RuntimeException {
    SessionPersistenceException(String message) {
        super(message);
    }

    SessionPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}

final class SessionConflictException extends RuntimeException {
    SessionConflictException(String message) {
        super(message);
    }
}
