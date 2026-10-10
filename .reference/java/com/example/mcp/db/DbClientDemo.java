package com.example.mcp.db;

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
 * 验证客户端：不接 LLM，直接把 4 个工具跑一遍。
 *
 * 分三部分：
 *   ① 正常功能   —— 证明工具能用
 *   ② 攻击用例   —— 证明安全层挡得住
 *   ③ 误伤检查   —— 证明安全层没有过度拦截
 *
 * 第 ③ 部分最容易被忽略，但同样重要：
 * 一个把正常查询也拦掉的安全层，等于没有。
 *
 * 运行：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.db.DbClientDemo
 */
public final class DbClientDemo {

    public static void main(String[] args) {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();

        ServerParameters serverParams = ServerParameters.builder("java")
                .args("-Dfile.encoding=UTF-8",
                      "-Dstdout.encoding=UTF-8",
                      "-Dstderr.encoding=UTF-8",
                      "-classpath", System.getProperty("java.class.path"),
                      "com.example.mcp.db.DbMcpServer")
                .build();

        McpSyncClient client = McpClient.sync(new StdioClientTransport(serverParams, jsonMapper))
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        try {
            client.initialize();

            section("① 正常功能");

            show("list_tables", call(client, "list_tables", Map.of()));
            show("describe_table(spare_usage)",
                    call(client, "describe_table", Map.of("table_name", "spare_usage")));

            // 这就是最终要交给模型的典型问题
            show("run_query —— 9 月领用最多的 5 个备件",
                    call(client, "run_query", Map.of("sql", """
                            SELECT p.code, p.name, SUM(u.quantity) AS total
                            FROM spare_usage u
                            JOIN spare_part p ON p.id = u.part_id
                            WHERE u.used_at >= DATE '2026-09-01' AND u.used_at < DATE '2026-10-01'
                            GROUP BY p.code, p.name
                            ORDER BY total DESC
                            LIMIT 5
                            """)));

            section("② 攻击用例 —— 应该全部被拒绝");

            attack(client, "删表", "DELETE FROM spare_part");
            attack(client, "改数据", "UPDATE spare_stock SET quantity = 0");
            attack(client, "堆叠语句", "SELECT * FROM spare_part; DROP TABLE spare_part");
            attack(client, "SELECT INTO 建表", "SELECT * INTO backup FROM spare_part");
            attack(client, "读服务器文件", "SELECT FILE_READ('/etc/passwd')");
            attack(client, "注释隐藏关键字", "SEL/**/ECT 1; DE/**/LETE FROM spare_part");
            attack(client, "行注释隐藏", "SELECT 1 -- \n; DROP TABLE spare_part");

            section("③ 误伤检查 —— 应该全部放行");

            pass(client, "字符串里的关键字不算数",
                    "SELECT * FROM spare_part WHERE name = 'delete me'");
            pass(client, "字段名含关键字子串",
                    "SELECT * FROM spare_part WHERE code LIKE 'UPDATED%'");
            // 这两条用 5000 行的 device_log，才能真实验证上限机制。
            // 用小表测等于没测——小表本来就查得完，看不出 LIMIT 有没有生效。
            pass(client, "没写 LIMIT（5000行表）",
                    "SELECT * FROM device_log");
            pass(client, "超大 LIMIT 应被收敛",
                    "SELECT * FROM device_log LIMIT 999999");
            pass(client, "聚合查询不受限（正确姿势）",
                    "SELECT level, COUNT(*) AS cnt FROM device_log GROUP BY level ORDER BY cnt DESC");
            pass(client, "CTE 查询",
                    "WITH recent AS (SELECT * FROM spare_usage WHERE used_at >= DATE '2026-09-01') "
                            + "SELECT part_id, SUM(quantity) FROM recent GROUP BY part_id");

            section("④ 最后一道墙 —— 数据库只读账号");

            System.out.println("上面 ② 测的是 SqlGuard（第二道墙）。");
            System.out.println("但它挡不住「绕过 Java 代码直接连库」的攻击，也挡不住 SqlGuard 自身被绕过。");
            System.out.println();
            System.out.println("最后一道墙是数据库权限本身，它需要单独验证：");
            System.out.println("    mvn -q compile exec:exec -DmainClass=com.example.mcp.db.ReadOnlyAccountDemo");
            System.out.println();
            System.out.println("（不能在这个类里测——H2 内存库是 per-JVM 的，");
            System.out.println("  这里的连接会开到本进程的空库，而不是 server 子进程里那个库。）");

        } finally {
            client.closeGracefully();
        }

        System.out.println();
        System.out.println("审计日志：" + AuditLog.logFile());
    }

    // ------------------------------------------------------------------

    private static void section(String title) {
        System.out.println();
        System.out.println("═".repeat(72));
        System.out.println("  " + title);
        System.out.println("═".repeat(72));
    }

    private static void show(String label, McpSchema.CallToolResult r) {
        System.out.println();
        System.out.println("── " + label + " ──");
        System.out.println(text(r));
    }

    private static void attack(McpSyncClient client, String label, String sql) {
        McpSchema.CallToolResult r = call(client, "run_query", Map.of("sql", sql));
        boolean blocked = Boolean.TRUE.equals(r.isError());
        System.out.printf("%-4s %-22s %s%n",
                blocked ? "✅" : "❌",
                label,
                blocked ? firstLine(text(r)) : "!!! 未被拦截： " + firstLine(text(r)));
    }

    private static void pass(McpSyncClient client, String label, String sql) {
        McpSchema.CallToolResult r = call(client, "run_query", Map.of("sql", sql));
        boolean ok = !Boolean.TRUE.equals(r.isError());
        System.out.printf("%-4s %-22s %s%n",
                ok ? "✅" : "❌",
                label,
                ok ? lastLine(text(r)) : "!!! 被误伤： " + firstLine(text(r)));
    }

    private static McpSchema.CallToolResult call(McpSyncClient client, String tool,
                                                 Map<String, Object> args) {
        return client.callTool(new McpSchema.CallToolRequest(tool, args));
    }

    private static String text(McpSchema.CallToolResult r) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : r.content()) {
            if (c instanceof McpSchema.TextContent t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    private static String firstLine(String s) {
        for (String line : s.split("\n")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return s.strip();
    }

    private static String lastLine(String s) {
        String[] lines = s.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].strip();
            }
        }
        return s.strip();
    }
}
