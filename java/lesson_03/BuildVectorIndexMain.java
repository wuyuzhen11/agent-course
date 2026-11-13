import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class BuildVectorIndexMain {
    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println(
                    "用法：BuildVectorIndexMain "
                            + "<文档路径> <输出路径> "
                            + "<maxChars> <overlapChars>"
            );
            System.exit(1);
        }

        Path documentFile = Path.of(args[0])
                .toAbsolutePath()
                .normalize();
        Path outputFile = Path.of(args[1])
                .toAbsolutePath()
                .normalize();
        int maxChars = Integer.parseInt(args[2]);
        int overlapChars = Integer.parseInt(args[3]);

        try {
            String markdown = Files.readString(
                    documentFile,
                    StandardCharsets.UTF_8
            );
            MarkdownChunker chunker = new MarkdownChunker();
            List<DocumentChunk> chunks = chunker.split(
                    markdown,
                    maxChars,
                    overlapChars
            );

            System.out.println("[文档] " + documentFile);
            System.out.println("[切块数量] " + chunks.size());
            System.out.println("[向量输出] " + outputFile);
        } catch (IOException error) {
            throw new UncheckedIOException(
                    "文档读取失败：" + documentFile,
                    error
            );
        }
    }

    private BuildVectorIndexMain() {
    }
}
