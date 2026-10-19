import java.util.List;
import java.util.Objects;

/** 把一批文本转换成固定维度的向量。 */
interface EmbeddingClient {
    /** 返回每个输入文本对应的向量，结果顺序与输入顺序一致。 */
    List<double[]> embed(List<String> texts);

    /** 当前客户端生成的向量维度。 */
    int dimensions();

    /** 单文本调用的便捷入口。 */
    default double[] embed(String text) {
        Objects.requireNonNull(text, "text 不能为 null");
        List<double[]> vectors = embed(List.of(text));
        if (vectors.size() != 1) {
            throw new IllegalStateException("embedding 客户端返回结果数量错误");
        }
        return vectors.get(0).clone();
    }
}
