/**
 * 允许模型使用的工具描述。
 *
 * parametersJson 保存 JSON Schema 原文；具体协议适配器负责把它放到
 * Responses 或 Chat Completions 请求的对应字段中。
 */
record ToolDefinition(
        String name,
        String description,
        String parametersJson,
        boolean strict
) {
}
