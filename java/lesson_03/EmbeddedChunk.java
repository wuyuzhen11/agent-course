import java.util.Objects;

record EmbeddedChunk(
        DocumentChunk chunk,
        double[] vector
) {
    EmbeddedChunk {
        Objects.requireNonNull(chunk, "chunk 不能为 null");
        Objects.requireNonNull(vector, "vector 不能为 null");

        if (vector.length == 0) {
            throw new IllegalArgumentException("vector 不能为空");
        }

        vector = vector.clone();
    }

    @Override
    public double[] vector() {
        return vector.clone();
    }
}