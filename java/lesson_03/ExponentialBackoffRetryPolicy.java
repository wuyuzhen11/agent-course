import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

final class ExponentialBackoffRetryPolicy implements RetryPolicy {
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_DELAY_MILLIS = 500L;
    private static final long MAX_DELAY_MILLIS = 4_000L;
    private static final long MAX_JITTER_MILLIS = 250L;

    @Override
    public int maxAttempts() {
        return MAX_ATTEMPTS;
    }

    @Override
    public boolean isRetryableStatus(int status) {
        return switch (status) {
            case 429, 500, 502, 503, 504 -> true;
            default -> false;
        };
    }

    @Override
    public Duration delay(
            int attempt,
            Optional<String> retryAfter
    ) {
        if (attempt < 1) {
            throw new IllegalArgumentException("重试次数必须从 1 开始");
        }

        long exponentialDelay = exponentialDelayMillis(attempt);
        long retryAfterDelay = parseRetryAfterMillis(retryAfter)
                .orElse(0L);
        long baseDelay = Math.min(
                MAX_DELAY_MILLIS,
                Math.max(exponentialDelay, retryAfterDelay)
        );
        long jitter = ThreadLocalRandom.current().nextLong(
                MAX_JITTER_MILLIS + 1
        );

        return Duration.ofMillis(Math.min(
                MAX_DELAY_MILLIS,
                baseDelay + jitter
        ));
    }

    @Override
    public boolean isRetryableException(IOException error) {
        return true;
    }

    private long exponentialDelayMillis(int attempt) {
        long delay = BASE_DELAY_MILLIS;
        for (int current = 1;
             current < attempt && delay < MAX_DELAY_MILLIS;
             current++) {
            delay = Math.min(MAX_DELAY_MILLIS, delay * 2);
        }
        return delay;
    }

    private Optional<Long> parseRetryAfterMillis(
            Optional<String> retryAfter
    ) {
        if (retryAfter.isEmpty()) {
            return Optional.empty();
        }

        try {
            long seconds = Long.parseLong(retryAfter.get().trim());
            if (seconds < 0) {
                return Optional.empty();
            }

            return Optional.of(Math.multiplyExact(seconds, 1_000L));
        } catch (NumberFormatException | ArithmeticException error) {
            return Optional.empty();
        }
    }


}
