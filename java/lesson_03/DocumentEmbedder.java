import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class DocumentEmbedder {
    private final EmbeddingClient embeddingClient;

    DocumentEmbedder(EmbeddingClient embeddingClient) {
        this.embeddingClient = Objects.requireNonNull(
                embeddingClient,
                "embeddingClient 不能为 null"
        );
    }

    List<EmbeddedChunk> embed(List<DocumentChunk> chunks) {
        Objects.requireNonNull(chunks, "chunks 不能为 null");

        List<String> texts = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            texts.add(chunk.text());
        }
        List<double[]> vectors = embeddingClient.embed(texts);

        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException(
                    "文档块数量与向量数量不一致"
            );
        }
        List<EmbeddedChunk> embeddedChunks =
                new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            embeddedChunks.add(new EmbeddedChunk(
                    chunks.get(i),
                    vectors.get(i)
            ));
        }

        return List.copyOf(embeddedChunks);
    }
}