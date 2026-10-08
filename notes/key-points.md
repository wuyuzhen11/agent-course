# 关键知识点索引

<!-- 格式：关键词 → 一句话解释 → 详见哪课笔记。面试前只过这一份。 -->

- 工具调用职责边界 → 模型生成工具调用请求或最终回答，Java 掌握校验、执行、结果回填和循环控制 → 详见 [L01](L01-agent-loop.md)
- 消息历史 → 按顺序保存用户消息、工具调用、工具结果和最终回答，作为下一次模型调用的执行上下文 → 详见 [L01](L01-agent-loop.md)
- `callId` → 将每个工具结果精确关联到原始工具调用，多个调用同时存在时防止结果错配 → 详见 [L01](L01-agent-loop.md)
- 模型调用预算 → `maxModelCalls` 限制一次任务中 `model.next()` 的总次数，预算耗尽后返回带计数和历史快照的 `FAILED` 降级结果 → 详见 [L04](L04-evaluation-and-robustness.md)
- Agent 评测 → 不只比较最终文字，还要检查工具轨迹、历史、调用次数和边界行为 → 详见 [L04](L04-evaluation-and-robustness.md)
- 参数错误回喂 → 工具把校验错误作为带 `callId` 的 `ToolResult` 写回历史，模型据此修正下一次调用 → 详见 [L04](L04-evaluation-and-robustness.md)
- 重复调用检测 → 用工具名和参数语义指纹识别重复请求，`callId` 只负责结果关联 → 详见 [L04](L04-evaluation-and-robustness.md)
- 评测隔离 → 每个场景创建新模型实例，单场失败转换为 `EvalResult` 后继续执行其余场景 → 详见 [L04](L04-evaluation-and-robustness.md)

