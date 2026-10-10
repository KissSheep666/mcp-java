package com.example.mcp.step4;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;

import com.example.mcp.step3.McpToClaudeTool;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 【第 4 步】把上面三步串起来 —— 完整的 agent loop。
 *
 * 一次完整的交互长这样：
 *
 *   用户提问
 *      │
 *      ▼
 *   ① 把 MCP 工具清单转成 Claude 的 tools，连同问题一起发给 Claude
 *      │
 *      ▼
 *   ② Claude 返回 stop_reason = "tool_use"，带着它想调的工具和参数
 *      │
 *      ▼
 *   ③ 我们拿着这个参数，通过 MCP client 调 MCP server
 *      │
 *      ▼
 *   ④ 把结果包成 tool_result 塞回对话，再发给 Claude
 *      │
 *      ▼
 *   ⑤ Claude 看到结果，返回 stop_reason = "end_turn" 和最终回答
 *      │
 *      ▼
 *    循环结束
 *
 * 所谓 "agent"，本质就是这个 while 循环。没有更玄的东西。
 *
 * 运行（需要 ANTHROPIC_API_KEY）：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.step4.AgentMain
 *
 * 也可以带自定义问题：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.step4.AgentMain \
 *       -Dexec.args="北京天气怎么样？另外帮我算 999 乘 111"   # 注意：没有乘法的工具，看它怎么办
 */
public final class AgentMain {

    /** 模型 ID。用当前最强的 Opus。 */
    private static final String MODEL = "claude-opus-5";

    /** 防御性上限：万一模型陷入循环，别让它无限烧 token。 */
    private static final int MAX_TURNS = 10;

    public static void main(String[] args) {
        // pom 里的 ${appArgs} 为空时会传进来一个空字符串，过滤掉
        String[] effective = java.util.Arrays.stream(args)
                .filter(a -> !a.isBlank())
                .toArray(String[]::new);

        String question = effective.length > 0
                ? String.join(" ", effective)
                : "北京天气怎么样？另外帮我算一下 1234 加 5678";

        System.out.println("用户: " + question);
        System.out.println("─".repeat(60));

        // ==================================================================
        // 准备阶段 A：起 MCP client（和 step2 完全一样）
        // ==================================================================
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();

        ServerParameters serverParams = ServerParameters.builder("java")
                .args("-Dfile.encoding=UTF-8",
                      "-Dstdout.encoding=UTF-8",
                      "-Dstderr.encoding=UTF-8",
                      "-classpath", System.getProperty("java.class.path"),
                      "com.example.mcp.step1.CalculatorServer")
                .build();

        McpSyncClient mcp = McpClient.sync(new StdioClientTransport(serverParams, jsonMapper))
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        // ==================================================================
        // 准备阶段 B：起 Claude client
        // 会自动读环境变量 ANTHROPIC_API_KEY
        // ==================================================================
        AnthropicClient claude = AnthropicOkHttpClient.fromEnv();

        try {
            mcp.initialize();

            // ==============================================================
            // ① 工具清单：MCP 发现 -> 翻译成 Claude 格式
            //
            // 这一步之后就再也不需要 mcp.listTools() 了。
            // 模型看到的是翻译后的 schema，它根本不知道背后是 MCP。
            // ==============================================================
            List<Tool> claudeTools = mcp.listTools().tools().stream()
                    .map(McpToClaudeTool::toClaudeTool)
                    .toList();

            System.out.println("已加载 " + claudeTools.size() + " 个 MCP 工具："
                    + claudeTools.stream().map(Tool::name).toList());
            System.out.println("─".repeat(60));

            // 对话历史。Claude API 是无状态的，每轮都要把完整历史发过去。
            List<MessageParam> messages = new ArrayList<>();
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(question)
                    .build());

            // ==============================================================
            // ② agent loop
            // ==============================================================
            for (int turn = 1; turn <= MAX_TURNS; turn++) {

                MessageCreateParams.Builder params = MessageCreateParams.builder()
                        .model(MODEL)
                        // maxTokens 是"思考 + 正文"的总上限。Opus 5 默认开启思考，
                        // 所以要给足余量，否则回答会在半路被截断。
                        .maxTokens(16000L)
                        // 想省钱/降延迟可以下调 effort，代价是推理深度：
                        // .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                        .messages(messages);

                claudeTools.forEach(params::addTool);

                Message response = claude.messages().create(params.build());

                // ---- 安全检查：模型可能因安全策略拒绝 ----
                // 拒绝时 content 可能是空的，下面读 content 前必须先判断。
                if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
                    System.out.println("模型拒绝了本次请求。");
                    response.stopDetails().ifPresent(d ->
                            System.out.println("  类别: " + d.category()));
                    return;
                }

                // ---- 收集这一轮的输出，同时执行工具 ----
                List<ContentBlockParam> assistantBlocks = new ArrayList<>();
                List<ContentBlockParam> toolResults = new ArrayList<>();

                for (ContentBlock block : response.content()) {
                    // toParam() 把响应块转成可以回传的参数块。
                    // 用官方提供的转换，不要手搓——thinking 块的 signature
                    // 必须原样保留，手动重建很容易踩雷。
                    assistantBlocks.add(block.toParam());

                    if (!block.isToolUse()) {
                        block.text().ifPresent(t -> System.out.println("Claude: " + t.text()));
                        continue;
                    }

                    // ======================================================
                    // ③ 模型想看某个工具了 —— 这里就是 MCP 出场的地方
                    // ======================================================
                    ToolUseBlock toolUse = block.toolUse().orElseThrow();
                    Map<String, Object> mcpArgs = toMap(toolUse);

                    System.out.println("  [调用] " + toolUse.name() + " " + mcpArgs);

                    // JsonValue -> Map，然后原样丢给 MCP client。
                    // 注意 model 给的参数没经过任何清洗，server 端的 schema
                    // 校验是唯一防线——这也是它默认开启的原因。
                    McpSchema.CallToolResult mcpResult = mcp.callTool(
                            new McpSchema.CallToolRequest(toolUse.name(), mcpArgs));

                    String resultText = extractText(mcpResult);
                    System.out.println("  [结果] " + resultText);

                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(resultText)
                                    // 工具报错时要显式标记 is_error，
                                    // 否则模型会把错误信息当成正常返回值继续往下编。
                                    .isError(Boolean.TRUE.equals(mcpResult.isError()))
                                    .build()));
                }

                // 把 assistant 这一轮说的话（含 tool_use 块）记进历史
                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(assistantBlocks)
                        .build());

                // ==============================================================
                // ⑤ 退出条件
                //
                // 没有工具要调，说明模型认为任务完成了。
                // 用 toolResults.isEmpty() 判断，比只看 stopReason 更稳——
                // 有些情况（比如它既说话又想调工具）stopReason 会有歧义。
                // ==============================================================
                if (toolResults.isEmpty()) {
                    System.out.println("─".repeat(60));
                    System.out.println("(共 " + turn + " 轮)");
                    return;
                }

                // ==============================================================
                // ④ 把工具结果回传，进入下一轮
                //
                // 注意：所有 tool_result 必须装在【同一条】 user 消息里。
                // 拆成多条会悄悄训练模型不再并行调用工具。
                // ==============================================================
                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(toolResults)
                        .build());
            }

            System.out.println("达到最大轮数 " + MAX_TURNS + "，强制结束。");

        } finally {
            mcp.closeGracefully();
        }
    }

    /** 把模型的工具入参转成普通 Map，好喂给 MCP。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(ToolUseBlock toolUse) {
        return toolUse._input().convert(Map.class);
    }

    /** CallToolResult.content() 是 Content 列表，文本在 TextContent 里。 */
    private static String extractText(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : result.content()) {
            if (c instanceof McpSchema.TextContent t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
