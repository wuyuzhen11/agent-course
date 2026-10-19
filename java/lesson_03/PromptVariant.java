import java.util.Objects;

/** 一组可参与 A/B 评测的系统提示词配置。 */
record PromptVariant(String name, SystemMessage systemMessage) {
    PromptVariant {
        Objects.requireNonNull(name, "name 不能为空");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name 必须包含有效文本");
        }
        Objects.requireNonNull(systemMessage, "systemMessage 不能为空");
    }
}
