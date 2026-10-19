import java.util.Objects;

record DocumentChunk(
        int index,
        String heading,
        String text
) {
    DocumentChunk {
        if (index < 0) {
            throw new IllegalArgumentException("index 不能小于零");
        }
        Objects.requireNonNull(heading, "heading 不能为 null");
        Objects.requireNonNull(text, "text 不能为 null");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text 必须包含有效内容");
        }

        heading = heading.strip();
        text = text.strip();
    }
}
