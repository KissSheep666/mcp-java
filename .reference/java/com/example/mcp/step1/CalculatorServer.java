package com.example.mcp.step1;

import com.example.mcp.core.StdioLifecycle;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/**
 * 【第 1 步】一个最小可用的 MCP Server。
 *
 * 关键认知：这个类里完全没有 LLM。
 * MCP Server 不知道 Claude 的存在，它只是一个"能被标准协议调用的工具集合"。
 * 任何 MCP 客户端（Claude Desktop、IDE、你自己写的程序）都能调它。
 *
 * 运行方式（server 不需要参数，它通过 stdin/stdout 通信）：
 *   mvn -q exec:exec -DmainClass=com.example.mcp.step1.CalculatorServer
 *
 * 但你不会想手动跑它——它是被客户端以子进程方式拉起来的。
 * 想单独验证，见 step2 的 ManualClient。
 */
public final class CalculatorServer {

    public static void main(String[] args) {
        // JSON 序列化实现。SDK 通过 SPI 找到 Jackson3 的实现，
        // 这个 Supplier 就是官方给的默认入口。
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();

        // ------------------------------------------------------------------
        // 1) 用 JSON Schema 描述工具的入参
        //
        // 注意：这就是一份普通的 JSON Schema。MCP 没有发明新的 schema 语言，
        // 它直接复用 JSON Schema。这一点很重要——后面接 Claude 时，
        // 这份 schema 几乎可以原样转成 Claude 的 tool.input_schema。
        // ------------------------------------------------------------------
        Map<String, Object> addSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "a", Map.of("type", "integer", "description", "第一个加数"),
                        "b", Map.of("type", "integer", "description", "第二个加数")
                ),
                "required", List.of("a", "b")
        );

        Map<String, Object> weatherSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of("type", "string", "description", "城市名，例如：北京")
                ),
                "required", List.of("city")
        );

        // ------------------------------------------------------------------
        // 2) 声明工具
        //
        // description 极其重要：模型就是靠它决定"什么时候该调这个工具"。
        // 写清楚"做什么"+"什么时候用"，比写清楚参数更重要。
        // ------------------------------------------------------------------
        McpSchema.Tool addTool = McpSchema.Tool.builder("add", addSchema)
                .description("计算两个整数之和。当用户询问加法运算时使用。")
                .build();

        McpSchema.Tool weatherTool = McpSchema.Tool.builder("get_weather", weatherSchema)
                .description("查询指定城市的当前天气。当用户询问天气时使用。")
                .build();

        // ------------------------------------------------------------------
        // 3) 组装并启动 server
        //
        // StdioServerTransportProvider：用标准输入/输出收发 JSON-RPC 消息。
        // 这也是为什么绝对不能用 System.out.println 打日志——会污染协议流。
        // ------------------------------------------------------------------
        StdioLifecycle.Handle lifecycle = StdioLifecycle.create(jsonMapper);

        McpSyncServer server = McpServer.sync(lifecycle.provider())
                // 名字和版本会在 initialize 握手时告诉客户端
                .serverInfo("calc-server", "1.0.0")
                // 能力协商：我提供 tools。客户端据此知道可以调 tools/list、tools/call
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())

                // toolCall 是注册工具的便捷写法，等价于构造一个 SyncToolSpecification。
                // lambda 的两个参数：
                //   exchange —— 可用于反向请求客户端（比如 sampling，让它帮忙调 LLM）
                //   request  —— 内含 name() 和 arguments()，arguments() 就是模型填的参数
                .toolCall(addTool, (exchange, request) -> {
                    int a = ((Number) request.arguments().get("a")).intValue();
                    int b = ((Number) request.arguments().get("b")).intValue();
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(a + " + " + b + " = " + (a + b))
                            .build();
                })

                .toolCall(weatherTool, (exchange, request) -> {
                    String city = String.valueOf(request.arguments().get("city"));
                    // 假的天气数据，用城市名做确定性哈希，保证每次演示结果一致
                    String[] conditions = {"晴", "多云", "小雨", "阴"};
                    int hash = Math.abs(city.hashCode());
                    String condition = conditions[hash % conditions.length];
                    int temp = 10 + hash % 20;
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(city + "：%s，%d°C".formatted(condition, temp))
                            .build();
                })

                .build();

        // 日志走 stderr。用 System.err 而不是 logger 只是为了让这个例子零配置可跑。
        System.err.println("[calc-server] 已启动，等待 stdin 上的 JSON-RPC 消息...");

        // 阻塞直到客户端断开（stdin EOF），然后干净退出。
        // 不要用 `new CountDownLatch(1).await()` 永久阻塞——
        // 那样客户端崩了之后 server 会变成孤儿进程。
        lifecycle.awaitClientDisconnect();
        try {
            server.closeGracefully();
        } catch (Exception ignored) {
            // 已经关了，正常
        }
    }
}
