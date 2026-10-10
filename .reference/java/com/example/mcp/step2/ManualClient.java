package com.example.mcp.step2;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.Map;

/**
 * 【第 2 步】一个纯 MCP 客户端 —— 注意，这里依然没有 LLM。
 *
 * 这是整个教程里最重要的一步，因为它证明了一件事：
 *   MCP 是"程序调用工具"的协议，不是"模型调用工具"的协议。
 *
 * 第 3、4 步才把 Claude 接进来，让模型来决定调哪个工具。
 * 把这两件事分开理解，你就不会再把 MCP 和 function calling 搞混。
 *
 * 运行：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.step2.ManualClient
 */
public final class ManualClient {

    public static void main(String[] args) {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();

        // ------------------------------------------------------------------
        // 1) 描述"怎么启动 server"
        //
        // 这就是 MCP 的核心设计：客户端不关心 server 是 Java、Python 还是 Node 写的，
        // 只关心"用什么命令能把它拉起来，然后用 stdio 说 JSON-RPC"。
        // 一个 Python 写的 server，这里就换成 ("python", "-m", "my_server")。
        // ------------------------------------------------------------------
        ServerParameters serverParams = ServerParameters.builder("java")
                // 关键：复用当前进程的 classpath，子进程才能找到 CalculatorServer。
                // 编码参数同样要带上，否则子进程打的日志会是乱码（见 pom.xml 里的说明）。
                .args("-Dfile.encoding=UTF-8",
                      "-Dstdout.encoding=UTF-8",
                      "-Dstderr.encoding=UTF-8",
                      "-classpath", System.getProperty("java.class.path"),
                      "com.example.mcp.step1.CalculatorServer")
                .build();

        // ------------------------------------------------------------------
        // 2) 建立连接
        //
        // StdioClientTransport 会在第一次通信时真正 fork 出 server 子进程。
        // MCP 有两种主流传输：STDIO（本地子进程）和 Streamable HTTP（远程服务）。
        // 旧的 HTTP+SSE 传输已废弃，新项目别再用。
        // ------------------------------------------------------------------
        McpSyncClient client = McpClient.sync(new StdioClientTransport(serverParams, jsonMapper))
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        try {
            // ------------------------------------------------------------------
            // 3) 握手：initialize
            //
            // 这一步会交换双方的身份和能力：
            //   客户端说：我支持 roots / sampling / elicitation
            //   server 说：我提供 tools / resources / prompts
            // 协商完成后，双方才知道接下来能发什么请求。
            // ------------------------------------------------------------------
            client.initialize();

            McpSchema.Implementation info = client.getServerInfo();
            System.out.println("已连接: " + info.name() + " v" + info.version());
            System.out.println("server 能力: " + client.getServerCapabilities());
            System.out.println();

            // ------------------------------------------------------------------
            // 4) tools/list —— 发现工具有哪些
            //
            // 返回的就是 step1 里声明的 name / description / inputSchema。
            // 在没有 LLM 的世界里，这一步的用途是让调用方（人或程序）知道能干什么。
            // 在第 3 步你会看到，它恰好就是喂给 Claude 的工具清单。
            // ------------------------------------------------------------------
            System.out.println("=== tools/list ===");
            McpSchema.ListToolsResult tools = client.listTools();
            for (McpSchema.Tool tool : tools.tools()) {
                System.out.println("• " + tool.name());
                System.out.println("  描述  : " + tool.description());
                System.out.println("  schema: " + tool.inputSchema());
            }
            System.out.println();

            // ------------------------------------------------------------------
            // 5) tools/call —— 真正调用
            //
            // 注意参数是一个普通 Map。没有魔法，没有模型，
            // 就是我们自己决定要调 add，自己填了 a=3, b=4。
            // ------------------------------------------------------------------
            System.out.println("=== tools/call ===");
            McpSchema.CallToolResult r1 = client.callTool(
                    new McpSchema.CallToolRequest("add", Map.of("a", 3, "b", 4)));
            System.out.println("add(3, 4)          -> " + text(r1));

            McpSchema.CallToolResult r2 = client.callTool(
                    new McpSchema.CallToolRequest("get_weather", Map.of("city", "北京")));
            System.out.println("get_weather(北京)  -> " + text(r2));

            // ------------------------------------------------------------------
            // 6) 服务端校验：故意传错参数类型
            //
            // SDK 默认会按 inputSchema 校验入参，不合法就返回 isError=true，
            // 根本不进 handler。这是一个容易被忽略但很有用的安全网。
            // 想关掉：server 端 .validateToolInputs(false)
            // ------------------------------------------------------------------
            System.out.println();
            System.out.println("=== 故意传错参数（a 传字符串）===");
            McpSchema.CallToolResult bad = client.callTool(
                    new McpSchema.CallToolRequest("add", Map.of("a", "不是数字", "b", 1)));
            System.out.println("isError = " + bad.isError());
            System.out.println("content = " + text(bad));

        } finally {
            // 必须优雅关闭：它会终止 fork 出来的 server 子进程。
            // 漏掉这行最典型的症状是跑完一次后，一堆 java 进程赖着不走。
            client.closeGracefully();
        }
    }

    /** CallToolResult.content() 是 Content 列表，文本在 TextContent 里。 */
    private static String text(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : result.content()) {
            if (c instanceof McpSchema.TextContent t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
