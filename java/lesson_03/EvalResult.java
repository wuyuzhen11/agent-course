record EvalResult(
        String name,
        boolean passed,
        String failureReason,
        int modelCalls,
        int toolCalls
) {
}