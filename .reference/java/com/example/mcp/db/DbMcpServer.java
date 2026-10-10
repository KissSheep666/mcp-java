package com.example.mcp.db;

import com.example.mcp.core.StdioLifecycle;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * 【工具层】只读数据库查询 MCP Server。
 *
 * 暴露 4 个工具：
 *   list_tables      —— 有哪些表
 *   describe_table   —— 某张表什么结构
 *   run_query        —— 执行只读 SELECT
 *   explain_query    —— 看执行计划，避免写出全表扫
 *
 * 分层结构（改动只影响最下面一层）：
 *
 *   ┌──────────────────────────────────────┐
 *   │ 本类：MCP 工具定义与 handler            │  ← 跟具体是什么库无关
 *   ├──────────────────────────────────────┤
 *   │ SqlGuard / AuditLog：安全层            │  ← 跟具体是什么库无关
 *   ├──────────────────────────────────────┤
 *   │ Db：数据源                             │  ← ★只有这层要换成你的库
 *   └──────────────────────────────────────┘
 */
public final class DbMcpServer {

    private static final int QUERY_TIMEOUT_SECONDS = 10;

    public static void main(String[] args) {
        // 演示模式：建库 + 灌测试数据 + 创建只读账号。
        // 设了 DB_URL 环境变量就自动跳过，改为校验外部测试库连通性。
        Db.init();
        System.err.println("[spare-db] 数据源: " + Db.describeTarget());

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();

        // ================================================================
        // 工具 1：list_tables
        // ================================================================
        McpSchema.Tool listTablesTool = McpSchema.Tool.builder("list_tables",
                        Map.of("type", "object", "properties", Map.of()))
                .description("列出数据库中所有的表，包含表名、行数和中文说明。"
                        + "在写任何查询之前，如果不确定有哪些表可用，先调用这个工具。")
                .build();

        // ================================================================
        // 工具 2：describe_table
        //
        // 这是 4 个工具里最重要的一个。
        // 模型 SQL 写得对不对，九成取决于它有没有看懂表结构。
        // ================================================================
        McpSchema.Tool describeTableTool = McpSchema.Tool.builder("describe_table",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "table_name", Map.of(
                                                "type", "string",
                                                "description", "表名，可先用 list_tables 查看有哪些表")),
                                "required", List.of("table_name")))
                .description("查看某张表的完整字段结构，包含字段名、类型、是否可空、主键标记和中文注释。"
                        + "写查询前如果不确定字段名或字段含义，必须先调用这个工具——"
                        + "不要凭猜测写字段名。")
                .build();

        // ================================================================
        // 工具 3：run_query
        // ================================================================
        McpSchema.Tool runQueryTool = McpSchema.Tool.builder("run_query",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "sql", Map.of(
                                                "type", "string",
                                                "description", "一条 SELECT 语句。不要带分号，不要写多条语句。")),
                                "required", List.of("sql")))
                .description("执行一条只读 SELECT 查询并返回结果表格。"
                        + "只允许 SELECT，任何写操作都会被拒绝。结果最多返回 "
                        + SqlGuard.MAX_ROWS + " 行，超出会截断。"
                        + "建议：先用 list_tables 和 describe_table 确认表结构，再写查询。")
                .build();

        // ================================================================
        // 工具 4：explain_query
        // ================================================================
        McpSchema.Tool explainQueryTool = McpSchema.Tool.builder("explain_query",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "sql", Map.of(
                                                "type", "string",
                                                "description", "要分析的 SELECT 语句。")),
                                "required", List.of("sql")))
                .description("查看一条查询的执行计划，用来判断它会不会全表扫描。"
                        + "当你不确定某个查询在大表上是否高效时使用。")
                .build();

        // ================================================================
        // 组装
        // ================================================================
        StdioLifecycle.Handle lifecycle = StdioLifecycle.create(jsonMapper);

        McpSyncServer server = McpServer.sync(lifecycle.provider())
                .serverInfo("spare-db", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())

                .toolCall(listTablesTool, (exchange, request) -> {
                    long t0 = System.nanoTime();
                    try (Connection conn = Db.readOnlyConnection()) {
                        String out = SchemaIntrospector.listTables(conn);
                        AuditLog.record("list_tables", "-", true, "ok", ms(t0));
                        return text(out);
                    } catch (SQLException e) {
                        AuditLog.record("list_tables", "-", false, e.getMessage(), ms(t0));
                        return error("查询表列表失败：" + e.getMessage());
                    }
                })

                .toolCall(describeTableTool, (exchange, request) -> {
                    long t0 = System.nanoTime();
                    String table = String.valueOf(request.arguments().get("table_name"));
                    try (Connection conn = Db.readOnlyConnection()) {
                        String out = SchemaIntrospector.describeTable(conn, table);
                        AuditLog.record("describe_table", table, true, "ok", ms(t0));
                        return text(out);
                    } catch (SQLException e) {
                        AuditLog.record("describe_table", table, false, e.getMessage(), ms(t0));
                        return error("查询表结构失败：" + e.getMessage());
                    }
                })

                .toolCall(runQueryTool, (exchange, request) -> {
                    String sql = String.valueOf(request.arguments().get("sql"));
                    long t0 = System.nanoTime();

                    // ---- 安全层：校验 ----
                    SqlGuard.Verdict verdict = SqlGuard.inspect(sql);
                    if (!verdict.ok()) {
                        // 被拒绝也记审计——被拒绝的请求比成功的更有信息量
                        AuditLog.record("run_query", sql, false, verdict.reason(), ms(t0));
                        // 用 isError 返回，模型看到具体原因才知道怎么改
                        return error("SQL 被拒绝：" + verdict.reason());
                    }

                    // ---- 执行 ----
                    try (Connection conn = Db.readOnlyConnection();
                         Statement st = conn.createStatement()) {
                        st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                        try (ResultSet rs = st.executeQuery(verdict.sql())) {
                            String out = formatResultSet(rs);
                            AuditLog.record("run_query", sql, true, "ok", ms(t0));
                            return text(out);
                        }
                    } catch (SQLException e) {
                        AuditLog.record("run_query", sql, false, e.getMessage(), ms(t0));
                        // 把数据库的报错原样回给模型，它通常能自己改对
                        return error("SQL 执行失败：" + e.getMessage());
                    }
                })

                .toolCall(explainQueryTool, (exchange, request) -> {
                    String sql = String.valueOf(request.arguments().get("sql"));
                    long t0 = System.nanoTime();

                    SqlGuard.Verdict verdict = SqlGuard.inspect(sql);
                    if (!verdict.ok()) {
                        AuditLog.record("explain_query", sql, false, verdict.reason(), ms(t0));
                        return error("SQL 被拒绝：" + verdict.reason());
                    }

                    try (Connection conn = Db.readOnlyConnection();
                         Statement st = conn.createStatement()) {
                        st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                        try (ResultSet rs = st.executeQuery("EXPLAIN " + verdict.sql())) {
                            String out = formatResultSet(rs);
                            AuditLog.record("explain_query", sql, true, "ok", ms(t0));
                            return text(out);
                        }
                    } catch (SQLException e) {
                        AuditLog.record("explain_query", sql, false, e.getMessage(), ms(t0));
                        return error("EXPLAIN 失败：" + e.getMessage());
                    }
                })

                .build();

        System.err.println("[spare-db] 已启动。审计日志: " + AuditLog.logFile());

        // 阻塞直到客户端断开（stdin EOF），然后干净退出。
        // 这样不会留下孤儿进程，退出码也是 0。
        lifecycle.awaitClientDisconnect();
        try {
            server.closeGracefully();
        } catch (Exception ignored) {
            // 已经关了，正常
        }
    }

    // ------------------------------------------------------------------
    // 结果格式化
    // ------------------------------------------------------------------

    /**
     * 把 ResultSet 渲染成 Markdown 表格。
     *
     * 为什么用 Markdown 而不是 JSON？
     * 因为模型读表格比读 JSON 更准，而且 token 更省（不用重复字段名）。
     */
    private static String formatResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();

        StringBuilder sb = new StringBuilder();

        sb.append("| ");
        for (int i = 1; i <= cols; i++) {
            sb.append(md.getColumnLabel(i)).append(" | ");
        }
        sb.append('\n').append('|');
        for (int i = 1; i <= cols; i++) {
            sb.append("---|");
        }
        sb.append('\n');

        int rows = 0;
        while (rs.next()) {
            sb.append("| ");
            for (int i = 1; i <= cols; i++) {
                sb.append(cell(rs.getObject(i))).append(" | ");
            }
            sb.append('\n');
            rows++;
        }

        sb.append("\n（").append(rows).append(" 行）");
        if (rows >= SqlGuard.MAX_ROWS) {
            sb.append(" ⚠️ 已达返回上限 ").append(SqlGuard.MAX_ROWS)
              .append(" 行，结果可能被截断。如需完整数据请加聚合条件或缩小范围。");
        }
        return sb.toString();
    }

    /** 单元格渲染：处理 null、换行、长文本、竖线转义。 */
    private static String cell(Object value) {
        if (value == null) {
            return "NULL";
        }
        String s = String.valueOf(value)
                .replace("|", "\\|")     // 不转义会撑坏 Markdown 表格
                .replaceAll("\\s+", " ") // 换行会把表格打散
                .strip();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static McpSchema.CallToolResult text(String content) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(content)
                .isError(false)
                .build();
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(message)
                .isError(true)
                .build();
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
