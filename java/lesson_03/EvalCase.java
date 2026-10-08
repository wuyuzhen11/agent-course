import java.util.function.Supplier;

record EvalCase(
        String name,
        String question,
        Supplier<ModelClient> modelFactory,
        RunLimits limits,
        EvalVerifier verifier
) {
}