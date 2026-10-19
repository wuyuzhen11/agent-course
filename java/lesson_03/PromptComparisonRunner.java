import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 使用同一组场景比较多套系统提示词的评测结果。 */
final class PromptComparisonRunner {
    private final ToolExecutor tools;

    PromptComparisonRunner(ToolExecutor tools) {
        this.tools = Objects.requireNonNull(tools, "tools 不能为空");
    }

    List<PromptComparisonResult> run(
            List<EvalCase> evalCases,
            PromptVariant... variants
    ) {
        Objects.requireNonNull(evalCases, "evalCases 不能为空");
        if (evalCases.isEmpty()) {
            throw new IllegalArgumentException("evalCases 至少包含一个场景");
        }
        if (variants == null || variants.length < 2) {
            throw new IllegalArgumentException("A/B 评测至少需要两套提示词");
        }

        List<PromptComparisonResult> results = new ArrayList<>();
        for (PromptVariant variant : variants) {
            Objects.requireNonNull(variant, "variant 不能为空");
            System.out.println("=== Prompt 变体：" + variant.name() + " ===");
            List<EvalResult> evalResults = new EvalRunner(
                    tools,
                    variant.systemMessage()
            ).run(evalCases);
            results.add(new PromptComparisonResult(
                    variant.name(),
                    evalResults
            ));
        }

        printComparison(results);
        return List.copyOf(results);
    }

    private void printComparison(List<PromptComparisonResult> results) {
        System.out.println("=== Prompt A/B 对比 ===");
        for (PromptComparisonResult result : results) {
            System.out.printf(
                    "%s：通过 %d/%d，通过率 %.1f%%，平均模型调用 %.2f，平均工具调用 %.2f%n",
                    result.variantName(),
                    result.passedCount(),
                    result.results().size(),
                    result.passRate(),
                    result.averageModelCalls(),
                    result.averageToolCalls()
            );
        }
        if (results.size() >= 2) {
            PromptComparisonResult first = results.get(0);
            PromptComparisonResult second = results.get(1);
            System.out.printf(
                    "首个变体到第二个变体：通过率变化 %.1f 个百分点%n",
                    second.passRate() - first.passRate()
            );
        }
    }
}

record PromptComparisonResult(
        String variantName,
        List<EvalResult> results
) {
    PromptComparisonResult {
        Objects.requireNonNull(variantName, "variantName 不能为空");
        results = List.copyOf(Objects.requireNonNull(
                results,
                "results 不能为空"
        ));
        if (results.isEmpty()) {
            throw new IllegalArgumentException("results 至少包含一个场景");
        }
    }

    int passedCount() {
        return (int) results.stream()
                .filter(EvalResult::passed)
                .count();
    }

    double passRate() {
        return passedCount() * 100.0 / results.size();
    }

    double averageModelCalls() {
        return results.stream()
                .mapToInt(EvalResult::modelCalls)
                .average()
                .orElse(0.0);
    }

    double averageToolCalls() {
        return results.stream()
                .mapToInt(EvalResult::toolCalls)
                .average()
                .orElse(0.0);
    }
}
