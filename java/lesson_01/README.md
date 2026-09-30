# 第 1 节：亲手完成工具调用循环

彦祖，本节目标：你能根据消息历史组织模型和工具的执行，并解释每一步由谁负责。

本节使用 Java 17、预设模型响应和内存订单数据。它是消息编排练习；真实模型 API 接入放在下一节。预设模型通过规则读取问题中的第一个数字串作为订单号。学习完成后，真实模型将根据问题和工具描述作出选择。

## 本轮分工

- Codex 已准备：消息类型、预设模型、订单工具、启动程序与执行日志。
- 你负责：先描述执行流程，再实现 `AgentLoop.java` 的 `run(String question)`。
- 完成后：一起审查执行记录和失败原因，再做一个小的变化需求。

## 先读这两个文件

1. `LessonTypes.java`：消息和接口契约。
2. `AgentLoop.java`：本轮要完成的方法。

`LessonSupport.java` 提供 `ScriptedModel` 与 `OrderTools`，可在需要追踪具体行为时阅读。

| 类型 | 谁产生 | 含义 |
| --- | --- | --- |
| `UserMessage` | 程序根据用户输入构造 | 当前任务 |
| `ToolCall` | 模型 | 工具调用请求，包含调用 ID、名称、参数 |
| `ToolResult` | 程序执行工具后构造 | 工具结果，使用对应请求的调用 ID |
| `FinalAnswer` | 模型 | 任务的最终回复，包括信息不足时的澄清 |

`ModelReply` 有两种实现：`ToolCall` 和 `FinalAnswer`。本节每轮至多一个工具调用，Java 对象直接作为消息载体。真实 API 的 JSON 编解码、多工具调用和其他输出项会逐步加入。

## 先思考，再编码

任务输入：`查询订单 123`。

预期消息顺序：

```text
UserMessage("查询订单 123")
ToolCall(callId="call_1", name="get_order", arguments={orderId="123"})
ToolResult(callId="call_1", content="订单 123，状态：已发货……")
FinalAnswer("查询结果：订单 123，状态：已发货……")
```

请先用自己的话描述：历史在哪里创建、每轮追加哪些消息、遇到两类模型回复分别做什么、何时结束。你可以直接把思路发到对话里，我们先校准，再写代码。

伪代码提示：

```text
为本次任务创建历史，并加入用户问题
在模型调用次数预算内：
    把历史与工具定义交给模型
    记录模型回复
    根据回复类型完成对应动作
达到调用上限时给出明确的终止原因
```

可用接口：

```java
ModelReply reply = model.next(history, tools.definitions());
ToolResult result = tools.execute(toolCall);
```

`maxModelCalls` 表示最多调用 `model.next` 的次数。正常查询需要两次模型调用。每次 `run` 使用独立的历史；模型返回最终回答时可以立即返回；持续请求工具时由程序控制结束。

## 运行

在本节目录打开 PowerShell：

```powershell
Set-Location 'D:\codex_work_place\agent\course\java\lesson_01'
.\run.ps1 -ShowHelp
.\run.ps1 -Question '查询订单 123'
```

`run.ps1` 在 Java 源码更新时编译，后续运行复用编译结果。工程只使用 JDK 标准库。

初始骨架中的 `run` 是你的练习入口，运行查询会提示完成该方法，并以状态码 2 结束。完成方法后再验证以下场景。

| 命令参数 | 预期行为 |
| --- | --- |
| `-Question '查询订单 123'` | 两次模型调用；执行一次订单查询；最终回答包含已发货 |
| `-Question '查询订单 456'` | 最终回答包含待发货 |
| `-Question '查询订单 999'` | 工具返回 `ORDER_NOT_FOUND`，模型根据结果提示核对订单号 |
| `-Question '帮我查一下订单'` | 一次模型调用；最终回答提示提供订单号 |
| `-Question '查询订单 123' -Repeat` | 模型持续请求工具；最多三次模型调用后由程序明确结束 |

查看 `[模型收到的历史]`、`[模型回复]`、`[Java 执行工具]`、`[工具结果]` 四类日志，还原整个执行过程。

本轮完成标准：你能独立实现上述流程，并解释工具请求为什么先于工具结果进入历史，以及 `callId` 如何将二者关联。
