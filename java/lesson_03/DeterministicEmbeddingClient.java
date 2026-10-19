import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 使用字符 n-gram 特征哈希生成确定性向量，不访问远程 API。
 *
 * <p>这是本地测试基线：相同文本始终得到相同向量，词面相近的文本
 * 通常会共享更多特征。它不具备真实 embedding 模型的语义理解能力。</p>
 */
final class DeterministicEmbeddingClient implements EmbeddingClient {
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final int MIN_NGRAM = 1;
    private static final int MAX_NGRAM = 5;

    private final int dimensions;

    DeterministicEmbeddingClient() {
        this(256);
    }

    DeterministicEmbeddingClient(int dimensions) {
        if (dimensions < 1) {
            throw new IllegalArgumentException("dimensions 至少为 1");
        }
        this.dimensions = dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<double[]> embed(List<String> texts) {
        Objects.requireNonNull(texts, "texts 不能为 null");
        List<double[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embedText(text));
        }
        return List.copyOf(vectors);
    }

    private double[] embedText(String text) {
        Objects.requireNonNull(text, "text 不能为 null");
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("text 必须包含有效内容");
        }

        int[] codePoints = normalized.codePoints().toArray();
        double[] vector = new double[dimensions];
        for (int ngramSize = MIN_NGRAM;
                ngramSize <= MAX_NGRAM;
                ngramSize++) {
            for (int start = 0;
                    start + ngramSize <= codePoints.length;
                    start++) {
                String feature = new String(
                        codePoints,
                        start,
                        ngramSize
                );
                long hash = hashFeature(ngramSize, feature);
                int bucket = (int) Math.floorMod(hash, (long) dimensions);
                double sign = (hash & 1L) == 0L ? 1.0 : -1.0;
                vector[bucket] += sign;
            }
        }
        normalize(vector);
        return vector;
    }

    private String normalize(String text) {
        return text
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private long hashFeature(int ngramSize, String feature) {
        long hash = FNV_OFFSET_BASIS ^ ngramSize;
        byte[] bytes = feature.getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) {
            hash ^= Byte.toUnsignedInt(value);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private void normalize(double[] vector) {
        double squaredNorm = 0.0;
        for (double value : vector) {
            squaredNorm += value * value;
        }
        if (squaredNorm == 0.0) {
            return;
        }

        double norm = Math.sqrt(squaredNorm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }
}
