package com.example.mcp.core;

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
import java.util.function.Consumer;

/**
 * 可复用的 agent loop：接一个 MCP Server，然后对话。
 *
 * 和 step4/AgentMain 的关系：
 *   step4/AgentMain 把循环写在 main 里，是为了教学——
 *   你可以在一屏内看完整条链路，不用跳文件。
 *   这个类是把同一套逻辑抽出来给实际项目用。
 *
 * 用法：
 *   try (McpAgent agent = McpAgent.connect("com.example.mcp.db.DbMcpServer")) {
 *       agent.ask("9 月份领用最多的备件是什么？");
 *   }
 */
public final class McpAgent implements AutoCloseable {

    private static final String MODEL = "claude-opus-5";

    /** 防御性上限：模型偶尔会陷入循环。 */
    private static final int MAX_TURNS = 12;

    /**
     * 回灌给模型的工具结果长度上限。
     *
     * 为什么需要这个？因为工具结果会原样进入对话历史，
     * 而对话历史每一轮都要完整重发。一个 200 行的表格塞进去，
     * 之后每一轮都在为它付 input token——成本会指数级放大。
     *
     * 真实项目里更常见的做法是：只回传摘要 + 让模型按需再查。
     */
    private static final int MAX_TOOL_RESULT_CHARS = 4_000;

    private final McpSyncClient mcp;
    private final List<Tool> tools;
    private final Consumer<String> out;

    /**
     * Claude 客户端【懒加载】。
     *
     * 这样 connect() 不需要 ANTHROPIC_API_KEY ——
     * 你可以先只连 MCP 自检（--dry-run），确认工具都加载对了，
     * 再去花 API 的钱。顺便也让"连不上 server"和"没配 Key"
     * 这两类错误分开了，排查起来清楚得多。
     */
    private AnthropicClient claude;

    /** 模型最近一次输出的正文，循环结束时返回。 */
    private String lastText = "";

    private McpAgent(McpSyncClient mcp, List<Tool> tools, Consumer<String> out) {
        this.mcp = mcp;
        this.tools = tools;
        this.out = out;
    }

    private AnthropicClient claude() {
        if (claude == null) {
            claude = AnthropicOkHttpClient.fromEnv();
        }
        return claude;
    }

    /**
     * 连上一个 MCP Server。
     *
     * @param serverMainClass server 的主类全名，比如 "com.example.mcp.db.DbMcpServer"。
     *                        这里刻意用「类名」而不是「命令」，是因为本教程的所有
     *                        server 都是同一个 Java 工程里的类。真实场景通常是
     *                        ServerParameters.builder("npx").args("-y", "@xxx/mcp-server")
     *                        这种形式——MCP 不关心 server 是什么语言写的。
     */
    public static McpAgent connect(String serverMainClass) {
        return connect(serverMainClass, System.out::println);
    }

    public static McpAgent connect(String serverMainClass, Consumer<String> out) {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();

        ServerParameters serverParams = ServerParameters.builder("java")
                .args("-Dfile.encoding=UTF-8",
                      "-Dstdout.encoding=UTF-8",
                      "-Dstderr.encoding=UTF-8",
                      "-classpath", System.getProperty("java.class.path"),
                      serverMainClass)
                .build();

        McpSyncClient mcp = McpClient.sync(new StdioClientTransport(serverParams, jsonMapper))
                // 数据库查询可能慢，超时给宽一点
                .requestTimeout(Duration.ofSeconds(60))
                .build();

        // 握手失败会在这里抛异常，越早失败越好
        mcp.initialize();

        // MCP 发现 → Claude 工具格式
        List<Tool> tools = mcp.listTools().tools().stream()
                .map(McpToClaudeTool::toClaudeTool)
                .toList();

        out.accept("已连接 " + mcp.getServerInfo().name()
                + "，加载 " + tools.size() + " 个工具："
                + tools.stream().map(Tool::name).toList());

        return new McpAgent(mcp, tools, out);
    }

    /**
     * 问一个问题，跑完整个 agent loop。
     *
     * @return 模型的最终回答；被拒绝或达到轮数上限时返回空串
     */
    public String ask(String question) {
        out.accept("");
        out.accept("用户: " + question);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(question)
                .build());

        for (int turn = 1; turn <= MAX_TURNS; turn++) {

            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(MODEL)
                    // Claude Opus 5 默认开启思考，maxTokens 是「思考 + 正文」的总上限，
                    // 给紧会让回答半路被截断
                    .maxTokens(16000L)
                    .messages(messages);
            tools.forEach(params::addTool);

            Message response = claude().messages().create(params.build());

            // 安全策略可能拒绝。拒绝时 content 可能是空的，必须先判断再读。
            if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
                out.accept("[模型拒绝了本次请求]");
                response.stopDetails().ifPresent(d -> out.accept("  类别: " + d.category()));
                return "";
            }

            List<ContentBlockParam> assistantBlocks = new ArrayList<>();
            List<ContentBlockParam> toolResults = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                // 用官方 toParam()，不要手搓——thinking 块的 signature
                // 必须逐字节原样保留，手动重建会破坏它
                assistantBlocks.add(block.toParam());

                if (!block.isToolUse()) {
                    block.text().ifPresent(t -> finalTextHolder(out, t.text()));
                    continue;
                }

                ToolUseBlock toolUse = block.toolUse().orElseThrow();
                Map<String, Object> mcpArgs = toolUse._input().convert(Map.class);

                out.accept("  [调用] " + toolUse.name() + " " + summarizeArgs(mcpArgs));

                toolResults.add(invokeMcpTool(toolUse, mcpArgs));
            }

            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .contentOfBlockParams(assistantBlocks)
                    .build());

            // 没有工具要调 = 任务完成
            if (toolResults.isEmpty()) {
                out.accept("（共 " + turn + " 轮）");
                return lastText;
            }

            // 所有 tool_result 必须在同一条 user 消息里。
            // 拆成多条会悄悄训练模型不再并行调用工具。
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(toolResults)
                    .build());
        }

        out.accept("[达到最大轮数 " + MAX_TURNS + "，强制结束]");
        return lastText;
    }

    /** 已加载的工具名。用于自检，不需要调用模型。 */
    public List<String> toolNames() {
        return tools.stream().map(Tool::name).toList();
    }

    // ------------------------------------------------------------------

    private void finalTextHolder(Consumer<String> out, String text) {
        lastText = text;
        out.accept(text);
    }

    /** 调 MCP 工具，把结果包成 tool_result 块。 */
    private ContentBlockParam invokeMcpTool(ToolUseBlock toolUse, Map<String, Object> args) {
        try {
            McpSchema.CallToolResult r = mcp.callTool(
                    new McpSchema.CallToolRequest(toolUse.name(), args));

            String text = extractText(r);
            boolean isError = Boolean.TRUE.equals(r.isError());

            out.accept("  [" + (isError ? "错误" : "结果") + "] " + firstLines(text, 6));

            return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(toolUse.id())
                    .content(cap(text))
                    // 必须显式标记错误，否则模型会把报错当成正常返回值继续编
                    .isError(isError)
                    .build());

        } catch (Exception e) {
            // 工具本身崩了（server 挂了、超时了），也要回一条 tool_result，
            // 否则对话会卡在"有个 tool_use 没有对应结果"的非法状态
            String msg = "工具调用失败: " + e.getMessage();
            out.accept("  [错误] " + msg);
            return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(toolUse.id())
                    .content(msg)
                    .isError(true)
                    .build());
        }
    }

    private static String cap(String text) {
        if (text.length() <= MAX_TOOL_RESULT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_TOOL_RESULT_CHARS)
                + "\n…（结果过长已截断。如需完整数据，请缩小查询范围或使用聚合）";
    }

    private static String extractText(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : result.content()) {
            if (c instanceof McpSchema.TextContent t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    private static String summarizeArgs(Map<String, Object> args) {
        String s = String.valueOf(args);
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static String firstLines(String text, int n) {
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, lines.length); i++) {
            if (!lines[i].isBlank()) {
                sb.append(lines[i].strip());
                if (i < n - 1 && i < lines.length - 1) {
                    sb.append(" / ");
                }
            }
        }
        String s = sb.toString();
        return s.length() > 300 ? s.substring(0, 300) + "…"
                : (lines.length > n ? s + " …（共 " + lines.length + " 行）" : s);
    }

    @Override
    public void close() {
        // 不调用的话，server 子进程会赖着不走
        mcp.closeGracefully();
    }
}
