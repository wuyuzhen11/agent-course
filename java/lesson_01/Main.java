public class Main {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--help")) {
            System.out.println("本地预设响应练习，运行环境：Java 17。");
            System.out.println("入口：AgentLoop.java 中的 run 方法。");
            System.out.println("运行：.\\run.ps1 -Question '查询订单 123'");
            System.out.println("持续调用场景：.\\run.ps1 -Question '查询订单 123' -Repeat");
            return;
        }
        boolean repeat = args.length > 0 && args[0].equals("--repeat");
        int questionIndex = repeat ? 1 : 0;
        String question = args.length > questionIndex ? args[questionIndex] : "查询订单 123";
        System.out.println("[练习模式] 使用本地预设模型响应和内存订单数据。");
        System.out.println("[用户问题] " + question);
        var agent = new AgentLoop(new ScriptedModel(repeat), new OrderTools(), 3);
        try {
            System.out.println("[最终回答] " + agent.run(question));
        } catch (UnsupportedOperationException | IllegalStateException error) {
            System.err.println("[任务结束] " + error.getMessage());
            System.exit(2);
        }
    }
}
