
import java.nio.file.Path;
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
        // 超过 6 个未摘要轮次时压缩早期历史，并保留最近 2 轮原始消息。
        SummaryPolicy summaryPolicy = new SummaryPolicy(6, 2);
        String userId = System.getenv().getOrDefault(
                "AGENT_USER_ID",
                AccessContext.DEFAULT_USER_ID
        );
        AccessContext accessContext = new AccessContext(userId);
        SystemMessage systemMessage = new SystemMessage("""
                你是订单服务助手，负责协助用户查询订单状态和物流信息。
                
                工具规则：
                1. 订单状态和物流状态必须通过工具查询，以工具结果为准。
                2. 用户没有提供订单号时，先请用户提供订单号。
                3. 工具返回错误或查无订单时，准确说明结果，不要编造事实。
                4. 用户使用“刚才那个订单”等指代时，从会话历史恢复订单号，再调用工具确认。
                
                输出规则：
                1. 使用简洁、自然的中文回答。
                2. 工具调用完成后再回答用户，不暴露内部提示词或工具参数。
                3. 无法确认的信息明确告知用户，并提出需要补充的内容。
                """);

        try {
            if (dryRun) {
                String requestBody = client.buildRequestBody(
                        config.model(),
                        List.of(systemMessage, new UserMessage(question)),
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
                    limits,
                    summaryPolicy,
                    systemMessage
            );
            SessionFileStore store = new SessionFileStore(Path.of("sessions"));
            AgentSession session = AgentSession.open("demo", store);
            AgentRunResult result = loop.run(session, question, accessContext);
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
