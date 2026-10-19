import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarkdownChunker {
    private static final Pattern ATX_HEADING = Pattern.compile(
            "^\\s{0,3}(#{1,6})\\s+(.+?)\\s*$"
    );

    List<DocumentChunk> split(
            String markdown,
            int maxChars,
            int overlapChars
    ) {
        Objects.requireNonNull(markdown, "markdown 不能为 null");
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars 至少为 1");
        }
        if (overlapChars < 0) {
            throw new IllegalArgumentException("overlapChars 不能小于零");
        }
        if (overlapChars >= maxChars) {
            throw new IllegalArgumentException(
                    "overlapChars 必须小于 maxChars"
            );
        }
        if (markdown.isBlank()) {
            return List.of();
        }

        List<Paragraph> paragraphs = parseParagraphs(markdown);
        List<DocumentChunk> chunks = new ArrayList<>();
        String currentHeading = "";
        StringBuilder currentText = new StringBuilder();

        for (Paragraph paragraph : paragraphs) {
            // 标题切换代表新的语义上下文，重叠窗口只在同一标题下延续。
            if (!currentText.isEmpty()
                    && !currentHeading.equals(paragraph.heading())) {
                addChunk(chunks, currentHeading, currentText);
            }
            currentHeading = paragraph.heading();

            for (String part : splitLongText(
                    paragraph.text(),
                    maxChars
            )) {
                if (currentText.isEmpty()) {
                    currentText.append(part);
                    continue;
                }

                int combinedLength = currentText.length()
                        + 2
                        + part.length();
                if (combinedLength <= maxChars) {
                    currentText.append("\n\n").append(part);
                    continue;
                }

                String previousText = currentText.toString();
                addChunk(chunks, currentHeading, currentText);
                currentText.append(overlapPrefix(
                        previousText,
                        part,
                        maxChars,
                        overlapChars
                ));
                currentText.append(part);
            }
        }

        if (!currentText.isEmpty()) {
            addChunk(chunks, currentHeading, currentText);
        }
        return List.copyOf(chunks);
    }

    private List<Paragraph> parseParagraphs(String markdown) {
        String normalized = markdown
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<Paragraph> paragraphs = new ArrayList<>();
        Deque<Heading> headings = new ArrayDeque<>();
        StringBuilder paragraph = new StringBuilder();
        boolean inFence = false;

        for (String line : lines) {
            String trimmed = line.strip();
            // 先处理代码围栏，避免代码里的 Markdown 标记改变文档结构。
            if (isFence(trimmed)) {
                inFence = !inFence;
                appendLine(paragraph, line);
                continue;
            }

            Matcher matcher = inFence
                    ? null
                    : ATX_HEADING.matcher(line);
            if (matcher != null && matcher.matches()) {
                addParagraph(
                        paragraphs,
                        headingPath(headings),
                        paragraph
                );
                int level = matcher.group(1).length();
                String title = matcher.group(2)
                        .replaceFirst("\\s+#+\\s*$", "")
                        .strip();
                while (!headings.isEmpty()
                        && headings.peekLast().level() >= level) {
                    headings.removeLast();
                }
                headings.addLast(new Heading(level, title));
                continue;
            }

            if (trimmed.isEmpty() && !inFence) {
                addParagraph(
                        paragraphs,
                        headingPath(headings),
                        paragraph
                );
                continue;
            }

            appendLine(paragraph, line);
        }

        addParagraph(
                paragraphs,
                headingPath(headings),
                paragraph
        );
        return paragraphs;
    }

    private void appendLine(StringBuilder paragraph, String line) {
        String content = line.stripTrailing();
        if (!paragraph.isEmpty()) {
            paragraph.append('\n');
        }
        paragraph.append(content);
    }

    private void addParagraph(
            List<Paragraph> paragraphs,
            String heading,
            StringBuilder paragraph
    ) {
        String text = paragraph.toString().strip();
        paragraph.setLength(0);
        if (!text.isEmpty()) {
            paragraphs.add(new Paragraph(heading, text));
        }
    }

    private String headingPath(Deque<Heading> headings) {
        return headings.stream()
                .map(Heading::title)
                .reduce((left, right) -> left + " > " + right)
                .orElse("");
    }

    private boolean isFence(String line) {
        return line.startsWith("```") || line.startsWith("~~~");
    }

    private List<String> splitLongText(String text, int maxChars) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int remaining = text.length() - start;
            if (remaining <= maxChars) {
                parts.add(text.substring(start).strip());
                break;
            }

            int end = safeEnd(text, start + maxChars);
            int cut = findNaturalCut(text, start, end);
            String part = text.substring(start, cut).strip();
            if (part.isEmpty()) {
                cut = end;
                part = text.substring(start, cut);
            }
            parts.add(part);
            start = cut;
            while (start < text.length()
                    && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
        }
        return parts;
    }

    private int findNaturalCut(String text, int start, int end) {
        int minimum = start + Math.max(1, (end - start) / 2);
        // 在句末或空白处分割，让固定长度限制尽量保持语义完整。
        for (int i = end - 1; i >= minimum; i--) {
            char current = text.charAt(i);
            if (Character.isWhitespace(current)
                    || current == '。'
                    || current == '！'
                    || current == '？'
                    || current == '；'
                    || current == '.'
                    || current == '!'
                    || current == '?'
                    || current == ';') {
                return i + 1;
            }
        }
        return end;
    }

    private int safeEnd(String text, int end) {
        if (end < text.length()
                && end > 0
                && Character.isLowSurrogate(text.charAt(end))
                && Character.isHighSurrogate(text.charAt(end - 1))) {
            return end - 1;
        }
        return end;
    }

    private String overlapPrefix(
            String previous,
            String next,
            int maxChars,
            int overlapChars
    ) {
        int available = maxChars - next.length();
        if (overlapChars == 0 || available <= 2) {
            return "";
        }

        int length = Math.min(overlapChars, available - 2);
        int start = Math.max(0, previous.length() - length);
        if (start > 0
                && Character.isLowSurrogate(previous.charAt(start))
                && Character.isHighSurrogate(previous.charAt(start - 1))) {
            start++;
        }
        return previous.substring(start).strip() + "\n\n";
    }

    private void addChunk(
            List<DocumentChunk> chunks,
            String heading,
            StringBuilder text
    ) {
        String value = text.toString().strip();
        if (!value.isEmpty()) {
            chunks.add(new DocumentChunk(chunks.size(), heading, value));
        }
        text.setLength(0);
    }

    private record Heading(int level, String title) {
    }

    private record Paragraph(String heading, String text) {
    }
}
