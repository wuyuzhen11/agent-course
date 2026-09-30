import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

// 预设规则用于练习编排；接入真实模型时，实现 ModelClient 即可替换这一层。
final class ScriptedModel implements ModelClient {
    private static final Pattern ORDER_ID = Pattern.compile("[0-9]+");
    private final boolean repeatTool;

    ScriptedModel(boolean repeatTool) {
        this.repeatTool = repeatTool;
    }

    @Override
    public ModelReply next(List<Message> history, List<ToolDefinition> tools) {
        System.out.println("[模型收到的历史] " + history);
        if (history.isEmpty()) {
            throw new IllegalArgumentException("先将 UserMessage 加入本次任务的历史。");
        }
        Message last = history.get(history.size() - 1);
        ModelReply reply;
        if (last instanceof UserMessage user) {
            var matcher = ORDER_ID.matcher(user.text());
            if (matcher.find()) {
                boolean toolAvailable = tools.stream()
                        .anyMatch(tool -> tool.name().equals("get_order"));
                if (toolAvailable) {
                    reply = new ToolCall("call_1", "get_order",
                            Map.of("orderId", matcher.group()));
                } else {
                    reply = new FinalAnswer("请提供 get_order 查询工具。");
                }
            } else {
                reply = new FinalAnswer("请提供订单号，例如 123。");
            }
        } else if (last instanceof ToolResult result) {
            if (history.size() >= 2
                    && history.get(history.size() - 2) instanceof ToolCall call
                    && call.callId().equals(result.callId())) {
                if (repeatTool) {
                    long count = history.stream().filter(ToolCall.class::isInstance).count();
                    reply = new ToolCall("call_" + (count + 1), "get_order", call.arguments());
                } else {
                    reply = new FinalAnswer("查询结果：" + result.content());
                }
            } else {
                throw new IllegalArgumentException("工具结果紧随对应 ToolCall，且两者 callId 相同。");
            }
        } else {
            throw new IllegalArgumentException("执行 ToolCall，并把 ToolResult 加入历史后继续。");
        }
        System.out.println("[模型回复] " + reply);
        return reply;
    }
}

final class OrderTools {
    private final Map<String, String> orders = Map.of(
            "123", "订单 123，状态：已发货，承运商：顺丰，预计送达：明天。",
            "456", "订单 456，状态：待发货，预计发货：今天。"
    );

    List<ToolDefinition> definitions() {
        return List.of(new ToolDefinition("get_order", "根据订单号查询订单状态", "orderId"));
    }

    ToolResult execute(ToolCall call) {
        String content;
        if (call.name().equals("get_order")) {
            if (call.arguments().keySet().equals(Set.of("orderId"))
                    && call.arguments().get("orderId").matches("[0-9]+")) {
                content = orders.getOrDefault(call.arguments().get("orderId"),
                        "ORDER_NOT_FOUND：请核对订单号。");
            } else {
                content = "INVALID_ARGUMENTS：参数为 orderId，值使用数字字符串。";
            }
        } else {
            content = "UNKNOWN_TOOL：可用工具为 get_order。";
        }
        ToolResult result = new ToolResult(call.callId(), content);
        System.out.println("[Java 执行工具] " + call);
        System.out.println("[工具结果] " + result);
        return result;
    }
}
