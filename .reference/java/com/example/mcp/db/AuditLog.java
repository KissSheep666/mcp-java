package com.example.mcp.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 【安全层·审计】把每一次工具调用记下来。
 *
 * 为什么这个不能省？
 *
 * 因为 agent 和普通程序有个本质区别：**它的行为是模型决定的，你无法预知**。
 * 你没法像 review 代码那样 review 模型下一步要干什么。
 *
 * 所以出事的时候（查错了数据、SQL 把库拖慢了、模型被提示注入操纵了），
 * 你唯一能依靠的就是"它到底干了什么"的记录。
 *
 * 记录内容刻意包含了**被拒绝的请求**——被拒绝的比成功的更有信息量，
 * 它告诉你模型想干什么但没干成。
 */
public final class AuditLog {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Path LOG_FILE = Path.of(
            System.getenv().getOrDefault("MCP_AUDIT_FILE", "mcp-audit.log"));

    private AuditLog() {
    }

    /**
     * 记一条。
     *
     * @param tool     工具名
     * @param sql      原始 SQL（模型生成的那份，不是加固后的——回溯时要看原样）
     * @param allowed  是否放行
     * @param detail   拒绝原因，或成功时的结果摘要
     * @param millis   耗时毫秒
     */
    public static synchronized void record(String tool, String sql,
                                           boolean allowed, String detail, long millis) {
        String line = "%s | %-14s | %-4s | %5dms | %s | %s%n".formatted(
                LocalDateTime.now().format(TS),
                tool,
                allowed ? "PASS" : "DENY",
                millis,
                oneLine(sql),
                oneLine(detail));

        // 审计日志写到文件，不是 stdout —— stdout 是 MCP 的协议通道
        try {
            Files.writeString(LOG_FILE, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 审计失败不能影响主流程，但必须让人看见
            System.err.println("[audit] 写入失败: " + e.getMessage());
        }
    }

    /** 压成单行，避免日志被换行撑爆。 */
    private static String oneLine(String s) {
        if (s == null) {
            return "-";
        }
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }

    public static Path logFile() {
        return LOG_FILE.toAbsolutePath();
    }
}
