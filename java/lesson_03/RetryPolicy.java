import java.time.Duration;
import java.util.Optional;

interface RetryPolicy {
    int maxAttempts();

    boolean isRetryableStatus(int status);

    Duration delay(
            int attempt,
            Optional<String> retryAfter
    );
}