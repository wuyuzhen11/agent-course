import java.util.List;

public final class EvalMain {
    public static void main(String[] args) {
        EvalCase order123 = new EvalCase(
                "订单 123 正常查询",
                "查询订单 123",
                () -> new SequenceModelClient(
                        new ModelTurn(
                                null,
                                List.of(new ToolCall(
                                        "call-1",
                                        "get_order",
                                        "{\"orderId\":\"123\"}"
                                ))
                        ),
                        new ModelTurn(
                                "订单 123 已发货",
                                List.of()
                        )
                ),
                new RunLimits(3, 2, 1),
                result -> {
                    require(
                            result.status() == RunStatus.SUCCEEDED,
                            "运行状态应该为 SUCCEEDED"
                    );
                    require(
                            result.finalAnswer().contains("已发货"),
                            "最终回答缺少订单状态"
                    );
                    require(
                            result.modelCalls() == 2,
                            "模型应该调用 2 次"
                    );
                    require(
                            result.toolCalls() == 1,
                            "工具应该调用 1 次"
                    );
                    require(
                            result.history().size() == 4,
                            "历史应该包含 4 条消息"
                    );

                    Message message = result.history().get(2);
                    require(
                            message instanceof ToolResult,
                            "历史第 3 条应该是工具结果"
                    );

                    ToolResult toolResult = (ToolResult) message;
                    require(
                            toolResult.output().contains("订单 123"),
                            "工具结果缺少订单 123"
                    );
                }
        );

        EvalCase order456 = new EvalCase(
                "订单 456 正常查询",
                "查询订单 456",
                () -> new SequenceModelClient(
                        new ModelTurn(
                                null,
                                List.of(new ToolCall(
                                        "call-1",
                                        "get_order",
                                        "{\"orderId\":\"456\"}"
                                ))
                        ),
                        new ModelTurn(
                                "订单 456 待发货",
                                List.of()
                        )
                ),
                new RunLimits(2, 1, 1),
                result -> {
                    require(
                            result.status() == RunStatus.SUCCEEDED,
                            "运行状态应该为 SUCCEEDED"
                    );
                    require(
                            result.finalAnswer().contains("待发货"),
                            "最终回答缺少订单状态"
                    );
                    require(
                            result.modelCalls() == 2,
                            "模型应该调用 2 次"
                    );
                    require(
                            result.toolCalls() == 1,
                            "工具应该调用 1 次"
                    );
                    require(
                            result.history().size() == 4,
                            "历史应该包含 4 条消息"
                    );

                    Message message = result.history().get(2);
                    require(
                            message instanceof ToolResult,
                            "历史第 3 条应该是工具结果"
                    );

                    ToolResult toolResult = (ToolResult) message;
                    require(
                            toolResult.output().contains("订单 456"),
                            "工具结果缺少订单 456"
                    );
                }
        );

        EvalCase order999 = new EvalCase(
                "订单 999 不存在",
                "查询订单 999",
                () -> new SequenceModelClient(
                        new ModelTurn(
                                null,
                                List.of(new ToolCall(
                                        "call-1",
                                        "get_order",
                                        "{\"orderId\":\"999\"}"
                                ))
                        ),
                        new ModelTurn(
                                "未查询到订单 999，请核对订单号",
                                List.of()
                        )
                ),
                new RunLimits(2, 1, 1),
                result -> {
                    require(
                            result.status() == RunStatus.SUCCEEDED,
                            "运行状态应该为 SUCCEEDED"
                    );
                    require(
                            result.finalAnswer().contains("未查询到"),
                            "最终回答没有说明订单不存在"
                    );
                    require(
                            result.modelCalls() == 2,
                            "模型应该调用 2 次"
                    );
                    require(
                            result.toolCalls() == 1,
                            "工具应该调用 1 次"
                    );
                    require(
                            result.history().size() == 4,
                            "历史应该包含 4 条消息"
                    );

                    Message message = result.history().get(2);
                    require(
                            message instanceof ToolResult,
                            "历史第 3 条应该是工具结果"
                    );

                    ToolResult toolResult = (ToolResult) message;
                    require(
                            toolResult.output().startsWith("ORDER_NOT_FOUND"),
                            "不存在的订单应该返回 ORDER_NOT_FOUND"
                    );
                }
        );
        EvalCase missingOrderId = new EvalCase(
                "缺少订单号",
                "帮我查询订单",
                () -> new SequenceModelClient(
                        new ModelTurn(
                                "请提供需要查询的订单号",
                                List.of()
                        )
                ),
                new RunLimits(2, 1, 1),
                result -> {
                    require(
                            result.finalAnswer().contains("订单号"),
                            "应该提示用户提供订单号"
                    );
                    require(result.modelCalls() == 1, "模型应该调用 1 次");
                    require(result.toolCalls() == 0, "工具调用应该为 0 次");
                    require(result.history().size() == 2, "历史应该有 2 条消息");
                }
        );
        EvalCase invalidArgumentsRecovery = new EvalCase(
                "非法参数后自纠",
                "查询订单 123",
                () -> new SequenceModelClient(
                        new ModelTurn(
                                null,
                                List.of(new ToolCall(
                                        "call-invalid",
                                        "get_order",
                                        "{\"orderId\":123}"
                                ))
                        ),
                        new ModelTurn(
                                null,
                                List.of(new ToolCall(
                                        "call-corrected",
                                        "get_order",
                                        "{\"orderId\":\"123\"}"
                                ))
                        ),
                        new ModelTurn(
                                "订单 123 已发货",
                                List.of()
                        )
                ),
                new RunLimits(4, 3, 1),
                result -> {
                    require(result.modelCalls() == 3, "模型应该调用 3 次");
                    require(result.toolCalls() == 2, "工具应该调用 2 次");
                    require(result.history().size() == 6, "历史应该有 6 条消息");

                    Message invalidMessage = result.history().get(2);
                    require(
                            invalidMessage instanceof ToolResult,
                            "第 3 条历史应该是非法参数结果"
                    );
                    ToolResult invalidResult = (ToolResult) invalidMessage;
                    require(
                            invalidResult.output().contains("INVALID_ARGUMENTS"),
                            "非法参数错误没有回填历史"
                    );

                    Message correctedMessage = result.history().get(4);
                    require(
                            correctedMessage instanceof ToolResult,
                            "第 5 条历史应该是纠正后的工具结果"
                    );
                    ToolResult correctedResult = (ToolResult) correctedMessage;
                    require(
                            correctedResult.output().contains("订单 123"),
                            "参数纠正后没有查到订单"
                    );
                }
        );

        EvalCase repeatedToolCall = new EvalCase(
                "重复工具调用防护",
                "查询订单 123",
                () -> new SequenceModelClient(
                        new ModelTurn(
                                null,
                                List.of(new ToolCall(
                                        "call-first",
                                        "get_order",
                                        "{\"orderId\":\"123\"}"
                                ))
                        ),
                        new ModelTurn(
                                null,
                                List.of(new ToolCall(
                                        "call-repeat",
                                        "get_order",
                                        "{ \"orderId\" : \"123\" }"
                                ))
                        ),
                        new ModelTurn(
                                "订单 123 已发货",
                                List.of()
                        )
                ),
                new RunLimits(4, 3, 1),
                result -> {
                    require(
                            result.status() == RunStatus.SUCCEEDED,
                            "重复调用防护后运行应该成功结束"
                    );
                    require(
                            result.finalAnswer().contains("已发货"),
                            "最终回答缺少首次查询得到的订单状态"
                    );
                    require(
                            result.modelCalls() == 3,
                            "模型应该调用 3 次"
                    );
                    require(
                            result.toolCalls() == 2,
                            "应该处理 2 个工具调用请求"
                    );
                    require(
                            result.history().size() == 6,
                            "历史应该有 6 条消息"
                    );

                    Message firstMessage = result.history().get(2);
                    require(
                            firstMessage instanceof ToolResult,
                            "第 3 条历史应该是首次工具结果"
                    );
                    ToolResult firstResult = (ToolResult) firstMessage;
                    require(
                            firstResult.output().contains("订单 123"),
                            "首次工具调用没有返回订单 123"
                    );

                    Message repeatedMessage = result.history().get(4);
                    require(
                            repeatedMessage instanceof ToolResult,
                            "第 5 条历史应该是重复调用结果"
                    );
                    ToolResult repeatedResult = (ToolResult) repeatedMessage;
                    require(
                            "call-repeat".equals(repeatedResult.callId()),
                            "重复调用结果没有关联原始 callId"
                    );
                    require(
                            repeatedResult.output().contains(
                                    "REPEATED_TOOL_CALL"
                            ),
                            "语义相同的重复调用没有被拦截"
                    );
                }
        );

        EvalCase modelBudgetExhausted = new EvalCase(
                "模型预算耗尽降级",
                "查询订单 123",
                () -> new SequenceModelClient(
                        new ModelTurn(
                                null,
                                List.of(new ToolCall(
                                        "call-invalid",
                                        "get_order",
                                        "{\"orderId\":123}"
                                ))
                        ),
                        new ModelTurn(
                                null,
                                List.of(new ToolCall(
                                        "call-corrected",
                                        "get_order",
                                        "{\"orderId\":\"123\"}"
                                ))
                        ),
                        new ModelTurn(
                                "订单 123 已发货",
                                List.of()
                        )
                ),
                new RunLimits(2, 3, 1),
                result -> {
                    require(
                            result.status() == RunStatus.FAILED,
                            "模型预算耗尽后状态应该为 FAILED"
                    );
                    require(
                            result.finalAnswer().contains("稍后重试"),
                            "预算耗尽后应该返回降级文案"
                    );
                    require(
                            result.modelCalls() == 2,
                            "模型调用应该停在 2 次"
                    );
                    require(
                            result.toolCalls() == 2,
                            "工具调用应该为 2 次"
                    );
                    require(
                            result.history().size() == 5,
                            "预算耗尽时历史应该保留 5 条消息"
                    );

                    Message lastMessage = result.history().get(4);
                    require(
                            lastMessage instanceof ToolResult,
                            "最后一条历史应该是第二次工具结果"
                    );
                    ToolResult lastResult = (ToolResult) lastMessage;
                    require(
                            lastResult.output().contains("订单 123"),
                            "预算耗尽前的正确工具结果应该保留"
                    );
                }
        );

        EvalRunner runner = new EvalRunner(new OrderTools());
        runner.run(List.of(
                order123,
                order456,
                order999,
                missingOrderId,
                invalidArgumentsRecovery,
                repeatedToolCall,
                modelBudgetExhausted
        ));
    }

    private static void require(
            boolean condition,
            String failureReason
    ) {
        if (!condition) {
            throw new AssertionError(failureReason);
        }
    }

    private EvalMain() {
    }
}
