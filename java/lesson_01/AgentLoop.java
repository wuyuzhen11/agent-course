import java.util.ArrayList;
import java.util.List;

/** 彦祖的练习入口：本轮只需实现 run。 */
final class AgentLoop {
    private final ModelClient model;
    private final OrderTools tools;
    private final int maxModelCalls;

    AgentLoop(ModelClient model, OrderTools tools, int maxModelCalls) {
        if (maxModelCalls < 1) {
            throw new IllegalArgumentException("maxModelCalls 至少为 1。");
        }
        this.model = model;
        this.tools = tools;
        this.maxModelCalls = maxModelCalls;
    }

    /**
     * 输入：本次用户问题。输出：模型的最终回答。
     * 每次执行 model.next 计一次模型调用。
     * 模型调用次数达到上限且任务仍在继续时，抛出 IllegalStateException。
     *
     * 可使用的接口：
     *   model.next(history, tools.definitions()) -> ModelReply
     *   tools.execute(toolCall) -> ToolResult
     *
     * 思路提示与验收场景见同目录 README.md。
     */
    String run(String question) {
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage(question));
        for (int i = 1; i <= maxModelCalls; i++) {
            ModelReply reply = model.next(history, tools.definitions());
            history.add(reply);
            if (reply instanceof ToolCall call) {
                ToolResult result = tools.execute(call);
                history.add(result);
            } else if (reply instanceof FinalAnswer answer) {
                return answer.text();
            }
        }
        throw new IllegalStateException("已达到模型调用次数上限：" + maxModelCalls);
    }
}
