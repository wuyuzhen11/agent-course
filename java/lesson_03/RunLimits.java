record RunLimits(
        int maxModelCalls,
        int maxToolCalls,
        int maxToolsPerTurn
) {
    RunLimits {
        if (maxModelCalls < 1) {
            throw new IllegalArgumentException(
                    "maxModelCalls 至少为 1"
            );
        }

        if (maxToolCalls < 1) {
            throw new IllegalArgumentException(
                    "maxToolCalls 至少为 1"
            );
        }

        if (maxToolsPerTurn < 1) {
            throw new IllegalArgumentException(
                    "maxToolsPerTurn 至少为 1"
            );
        }
    }
}