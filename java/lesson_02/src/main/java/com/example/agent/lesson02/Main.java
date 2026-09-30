package com.example.agent.lesson02;

public final class Main {
    public static void main(String[] args) {
        boolean dryRun = args.length > 0 && "--dry-run".equals(args[0]);
        int questionIndex = dryRun ? 1 : 0;
        String question = args.length > questionIndex ? args[questionIndex]
                : "用一句话解释 Agent 的工具调用循环。";

        ClientConfig config = ClientConfig.fromEnvironment();
        ApiProtocol protocol = ModelClients.resolveProtocol(config.model());

        try (ModelClient client = ModelClients.create(config, protocol)) {
            System.out.println("[协议] " + protocol.label());
            System.out.println("[模型服务] " + client.endpoint());
            System.out.println("[模型名称] " + config.model());
            System.out.println("[问题] " + question);

            if (dryRun) {
                System.out.println("[请求 JSON] " + client.buildRequestBody(question));
                System.out.println("[API Key 状态] " + (config.apiKey().isBlank() ? "未配置" : "已配置"));
                return;
            }

            config.requireApiKey();
            ModelResponse response = client.complete(question);
            System.out.println("[响应 ID] " + response.id());
            System.out.println("[状态] " + response.status());
            System.out.println("[Total Tokens] " + response.totalTokens());
            System.out.println("[回答] " + response.text());
        } catch (ModelApiException error) {
            System.err.println("[调用失败] " + error.getMessage());
            if (!error.responseBody().isBlank()) {
                System.err.println("[响应体] " + error.responseBody());
            }
            System.exit(2);
        }
    }
}
