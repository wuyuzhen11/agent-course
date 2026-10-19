import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class OrderTools implements ToolExecutor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, OrderData> orders = Map.of(
            "123", new OrderData(
                    AccessContext.DEFAULT_USER_ID,
                    "订单 123，状态：已发货"
            ),
            "456", new OrderData(
                    AccessContext.DEFAULT_USER_ID,
                    "订单 456，状态：待发货"
            )
    );

    private ToolResult accessDenied(String callId) {
        return new ToolResult(
                callId,
                "{\"code\":\"ACCESS_DENIED\",\"message\":\"当前用户无权查询该订单\"}"
        );
    }

    private ToolResult invalidArguments(String callId) {
        return new ToolResult(
                callId,
                "{\"code\":\"INVALID_ARGUMENTS\",\"message\":\"orderId 必须是数字字符串\"}"
        );
    }

    @Override
    public List<ToolDefinition> definitions() {
        ToolDefinition getOrder = new ToolDefinition(
                "get_order",
                "根据订单号查询订单状态",
                """
                {
                  "type": "object",
                  "properties": {
                    "orderId": {
                      "type": "string"
                    }
                  },
                  "required": ["orderId"],
                  "additionalProperties": false
                }
                """,
                true
        );

        return List.of(getOrder);
    }

    @Override
    public ToolResult execute(RunContext context, ToolCall call) {
        if (!"get_order".equals(call.name())) {
            return new ToolResult(
                    call.callId(),
                    "UNKNOWN_TOOL：可用工具为 get_order"
            );
        }

        if (call.arguments() == null || call.arguments().isBlank()) {
            return invalidArguments(call.callId());
        }

        try {
            JsonNode arguments = objectMapper.readTree(call.arguments());
            if (arguments == null || !arguments.isObject()) {
                return invalidArguments(call.callId());
            }

            JsonNode orderId = arguments.get("orderId");

            if (orderId == null
                    || !orderId.isTextual()
                    || !orderId.asText().matches("[0-9]+")
                    || arguments.size() != 1) {
                return invalidArguments(call.callId());
            }
            String value = orderId.asText();
            OrderData order = orders.get(value);
            if (order == null) {
                return new ToolResult(
                        call.callId(),
                        "ORDER_NOT_FOUND：请核对订单号"
                );
            }
            if (!order.ownerId().equals(context.accessContext().userId())) {
                return accessDenied(call.callId());
            }

            return new ToolResult(call.callId(), order.content());
        } catch (JsonProcessingException error) {
            return new ToolResult(
                    call.callId(),
                    "{\"code\":\"INVALID_JSON\",\"message\":\"arguments 不是合法 JSON\"}"
            );
        }

    }

    private record OrderData(String ownerId, String content) {
    }
}
