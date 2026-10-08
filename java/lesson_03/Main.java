
import java.util.List;

public final class Main {
    public static void main(String[] args) {
        boolean dryRun = args.length > 0 && "--dry-run".equals(args[0]);
        int questionIndex = dryRun ? 1 : 0;
        String question = args.length > questionIndex
                ? args[questionIndex]
                : "查询订单 123";

        ClientConfig config = ClientConfig.fromEnvironment();
        ApiProtocol protocol = ModelClients.resolveProtocol(config.model());
        HttpTransport transport = new JdkHttpTransport();
        RetryPolicy retryPolicy = new ExponentialBackoffRetryPolicy();
        AbstractModelApiClient client = ModelClients.create(config,protocol, transport,retryPolicy);
        OrderTools tools = new OrderTools();

        System.out.println("[协议] " + protocol.label());
        System.out.println("[模型服务] " + client.endpoint());
        System.out.println("[模型名称] " + config.model());
        System.out.println("[问题] " + question);

        RunLimits limits = new RunLimits(
                6,
                20,
                5
        );

        try {
            if (dryRun) {
                String requestBody = client.buildRequestBody(
                        config.model(),
                        List.of(new UserMessage(question)),
                        tools.definitions()
                );
                System.out.println("[请求 JSON] " + requestBody);
                System.out.println(
                        "[API Key 状态] "
                                + (config.apiKey().isBlank()
                                ? "未配置"
                                : "已配置")
                );
                return;
            }

            AgentLoop loop = new AgentLoop(
                    client,
                    tools,
                    limits
            );
            AgentRunResult result = loop.run(question);
            System.out.println("[最终回答] " + result.finalAnswer());
        } catch (ModelApiException error) {
            System.err.println("[调用失败] " + error.getMessage());
            if (!error.responseBody().isBlank()) {
                System.err.println("[响应体] " + error.responseBody());
            }
            System.exit(2);
        }
    }

    private Main() {
    }
}
