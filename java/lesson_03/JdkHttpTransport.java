import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

final class JdkHttpTransport implements HttpTransport {

    private final HttpClient http;

    JdkHttpTransport() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    JdkHttpTransport(HttpClient http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public HttpResponse<String> send(HttpRequest request)
            throws IOException, InterruptedException {
        return http.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
    }
}
