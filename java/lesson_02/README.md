# 第 2 节：调用真实模型（Responses 与 Chat 双协议）

本节在 Java 17 中调用中转服务的真实模型，先完成一次普通问答，再进入工具调用。程序按模型名自动选择协议，也支持手动覆盖。

## 当前配置

- 默认 Base URL：`https://token.seeworld.com:8443/v1`
- 默认模型：`gpt-5.6-sol`
- 协议路由：`gpt-` 前缀走 `POST /responses`；其余模型（如 `glm-5.3-flash`）走 `POST /chat/completions`
- 手动覆盖：设置 `OPENAI_API_PROTOCOL` 为 `responses` 或 `chat`
- API Key：只从当前 PowerShell 进程的 `OPENAI_API_KEY` 读取

## 先 DryRun

DryRun 只构造并打印请求，不访问网络，也不要求 API Key：

```powershell
Set-Location 'D:\codex_work_place\agent\course\java\lesson_02'
.\run.ps1 -DryRun
```

确认模型、问题和请求 JSON。请求体中的 `input` 就是本轮用户输入；工具调用接入后，这里会扩展为完整消息历史和工具定义。

## 设置 Key 并真实调用

在当前 PowerShell 会话执行：

```powershell
$env:OPENAI_API_KEY = "你的中转 Key"
.\run.ps1 -Question '用一句话解释 Agent 的工具调用循环。'
```

关闭这个 PowerShell 窗口后 Key 会消失。不要把真实 Key 写进代码、README 或配置文件。

成功时会打印响应 ID、状态、总 Token 数和模型回答。若中转不支持该模型或 Responses 协议，程序会保留 HTTP 状态和响应体，便于定位是网关兼容问题、鉴权问题还是模型问题。

## 阅读代码

| 文件 | 职责 |
| --- | --- |
| `ClientConfig.java` | 读取 Base URL、Key、模型名，并校验必要配置 |
| `ModelClient.java` | 模型客户端接口：发问答、构建请求体、报告端点 |
| `AbstractModelApiClient.java` | 共用 HTTP 骨架：发送请求、错误处理、超时与中断 |
| `ResponsesApiClient.java` | Responses 协议：构造 `input` 请求，解析 `output_text` |
| `ChatCompletionsApiClient.java` | Chat 协议：构造 `messages` 请求，解析 `choices[0].message.content` |
| `ModelClients.java` | 工厂：按模型名自动选协议，支持 `OPENAI_API_PROTOCOL` 覆盖 |
| `ModelApiException.java` | 保留 HTTP 状态与响应体，方便排查中转错误 |
| `Main.java` | CLI 入口，处理 DryRun 与真实调用 |

两个协议实现目前都是普通问答客户端。下一节会把协议适配层升级成第 1 节的 `ModelClient`，把工具定义按协议序列化进请求，并把模型返回的函数调用转换为 `ToolCall`。

## 排查顺序

1. DryRun：确认 Base URL、模型名、问题。
2. HTTP 401：Key 缺失或无效。
3. HTTP 404 且 `type=bad_response_status_code`：Key 和路径都有效，但当前模型的渠道不支持 Responses 协议；`gpt-5.6-sol` 已实测可用，`glm-5.3-flash` 这类 chat 协议模型需要走 `/chat/completions`。
4. 模型或参数错误：确认中转文档是否支持当前模型和 Responses 请求格式。
5. 返回体没有 `output_text`：保留响应体，检查中转是否返回了兼容层自定义结构。
