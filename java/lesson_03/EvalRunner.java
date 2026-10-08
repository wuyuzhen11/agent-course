import java.util.ArrayList;
import java.util.List;
final class EvalRunner {
    private final ToolExecutor tools;

    EvalRunner(ToolExecutor tools) {
        this.tools = tools;
    }

    EvalResult runOne(EvalCase evalCase) {
        AgentRunResult result;

        try {
            ModelClient model = evalCase.modelFactory().get();

            AgentLoop loop = new AgentLoop(
                    model,
                    tools,
                    evalCase.limits()
            );

            result = loop.run(evalCase.question());
        } catch (RuntimeException error) {
            return new EvalResult(
                    evalCase.name(),
                    false,
                    "执行异常：" + error.getClass().getSimpleName()
                            + "：" + error.getMessage(),
                    0,
                    0
            );
        }

        try {
            evalCase.verifier().verify(result);

            return new EvalResult(
                    evalCase.name(),
                    true,
                    "",
                    result.modelCalls(),
                    result.toolCalls()
            );
        } catch (AssertionError error) {
            return new EvalResult(
                    evalCase.name(),
                    false,
                    error.getMessage(),
                    result.modelCalls(),
                    result.toolCalls()
            );
        }
    }

    List<EvalResult> run(List<EvalCase> evalCases){
        List<EvalResult> results = new ArrayList<>();

        for (EvalCase evalCase : evalCases) {
            EvalResult result = runOne(evalCase);
            results.add(result);
            printResult(result);
        }

        printSummary(results);
        return List.copyOf(results);
    }

    private void printResult(EvalResult result) {
        String metrics = " | 模型调用 " + result.modelCalls()
                + " | 工具调用 " + result.toolCalls();

        if (result.passed()) {
            System.out.println(
                    "[PASS] " + result.name() + metrics
            );
            return;
        }

        System.out.println(
                "[FAIL] " + result.name()
                        + " | " + result.failureReason()
                        + metrics
        );
    }

    private void printSummary(List<EvalResult> results){
        long passed = results.stream()
                .filter(EvalResult::passed)
                .count();

        int total = results.size();

        double passRate = total == 0
                ? 0.0
                : passed * 100.0 / total;
        System.out.printf(
                "评测完成：%d/%d，通过率 %.1f%%%n",
                passed,
                total,
                passRate
        );
    }
}