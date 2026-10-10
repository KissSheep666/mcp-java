package com.example.mcp.db;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【安全层】SQL 校验器 —— 这是整个方案里最需要认真对待的代码。
 *
 * ══════════════════════════════════════════════════════════════════
 *  先读这段，再读代码
 * ══════════════════════════════════════════════════════════════════
 *
 * 这个类的职责是"挡住危险的 SQL"。但你必须清楚它的定位：
 *
 *   它是【第二道】墙，不是第一道，也不是唯一一道。
 *
 * 三道墙的分工：
 *   ① 数据库只读账号   ← 唯一不依赖"我的代码写得对"的一道，最重要
 *   ② 本类（SQL 校验）  ← 提供清晰错误信息，挡住绝大多数误操作
 *   ③ 超时 + 行数上限   ← 防止模型写出拖垮数据库的查询
 *
 * 为什么不能只靠 ②？
 *
 * 因为**用正则/分词来判断 SQL 安全性，理论上不可能做到完备**。
 * SQL 语法极其复杂，任何手写的解析器都有绕过路径。举个真实的例子：
 * 不同数据库的方言差异、嵌套注释、Unicode 同形字、
 * 存储过程里的动态 SQL……你不可能全部枚举。
 *
 * 所以正确的态度是：
 *   ② 用来挡"模型不小心写错"（占 99%），
 *   ① 用来挡"有人故意绕过"（占 1%，但后果严重）。
 *
 * 如果你只做了一件事，请做 ①。
 * ══════════════════════════════════════════════════════════════════
 */
public final class SqlGuard {

    /** 单次查询最多返回多少行。防止 SELECT * FROM 大表把内存打爆。 */
    public static final int MAX_ROWS = 200;

    /** SQL 最大长度。防止超长 SQL 攻击或模型跑飞。 */
    private static final int MAX_SQL_LENGTH = 8_000;

    /**
     * 禁止出现的关键字。
     *
     * 注意这是在【掩码后】的 SQL 上做整词匹配，所以：
     *   - 字符串字面量里的 'delete' 不会误伤（已被掩码成空格）
     *   - 列名 delete_flag 不会误伤（整词匹配，不是子串匹配）
     *
     * INTO 要特别说一下：SELECT ... INTO 会创建新表，是个写操作。
     * 这类"看起来是查询其实是写"的语法最容易漏。
     */
    private static final Set<String> BANNED_KEYWORDS = Set.of(
            // DML
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE", "TRUNCATE",
            // DDL
            "CREATE", "ALTER", "DROP", "RENAME", "COMMENT",
            // DCL / 权限
            "GRANT", "REVOKE",
            // 会话与事务控制
            "SET", "CALL", "EXEC", "EXECUTE", "PREPARE", "DEALLOCATE",
            // 写表语法
            "INTO",
            // 锁
            "LOCK", "UNLOCK",
            // H2 特有的危险函数/语句（能读写文件系统）
            "RUNSCRIPT", "SCRIPT", "BACKUP", "RESTORE", "CSVWRITE", "CSVREAD",
            "FILE_READ", "FILE_WRITE", "LINK_SCHEMA", "SHUTDOWN", "ATTACH", "DETACH",
            "CHECKPOINT", "EXPORT", "IMPORT"
    );

    /** 只允许以这些关键字开头。WITH 是 CTE，本质还是查询。 */
    private static final Set<String> ALLOWED_LEADING = Set.of("SELECT", "WITH");

    private static final Pattern LIMIT_PATTERN =
            Pattern.compile("(?i)\\blimit\\s+(\\d+)");

    private SqlGuard() {
    }

    /**
     * 校验结果。
     *
     * @param ok    是否放行
     * @param sql   放行时返回的、已加固的 SQL（可能被追加/收敛了 LIMIT）
     * @param reason 拒绝原因，会原样回给模型——这点很重要，
     *               模型看到具体原因才知道怎么改，而不是瞎猜
     */
    public record Verdict(boolean ok, String sql, String reason) {

        static Verdict pass(String sql) {
            return new Verdict(true, sql, null);
        }

        static Verdict reject(String reason) {
            return new Verdict(false, null, reason);
        }
    }

    /**
     * 校验并加固一条 SQL。
     *
     * @param rawSql 模型生成的原始 SQL，**完全不可信**
     */
    public static Verdict inspect(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return Verdict.reject("SQL 为空。");
        }

        String sql = rawSql.strip();

        // 去掉结尾的分号（单条语句末尾带分号是合法的，不该报错）
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).stripTrailing();
        }

        if (sql.isEmpty()) {
            return Verdict.reject("SQL 为空。");
        }

        if (sql.length() > MAX_SQL_LENGTH) {
            return Verdict.reject("SQL 过长（超过 " + MAX_SQL_LENGTH + " 字符）。");
        }

        // ---------------------------------------------------------------
        // 掩码：把字符串字面量、引号标识符、注释全部替换成等长空格。
        //
        // 为什么要"等长"？因为这样掩码串和原串的字符下标一一对应，
        // 后面改 LIMIT 时可以直接用掩码里找到的位置去改原串。
        //
        // 为什么要掩码而不是直接正则匹配？
        // 因为 SELECT '这里写 DELETE 不算数' 里的 DELETE 是数据不是语法。
        // 不掩码就会误伤，或者反过来被构造的字符串骗过。
        // ---------------------------------------------------------------
        String masked = mask(sql);

        // 掩码后再查分号：能挡住 SELECT 1; DELETE FROM t 这种堆叠语句
        if (masked.indexOf(';') >= 0) {
            return Verdict.reject("不允许一次执行多条 SQL 语句。请只提交一条 SELECT。");
        }

        List<String> tokens = tokenize(masked);

        if (tokens.isEmpty()) {
            return Verdict.reject("SQL 为空。");
        }

        String leading = tokens.get(0);
        if (!ALLOWED_LEADING.contains(leading)) {
            return Verdict.reject(
                    "只允许 SELECT 查询，当前以 " + leading + " 开头。"
                            + "如果你需要看表结构，请使用 describe_table 工具。");
        }

        for (String token : tokens) {
            if (BANNED_KEYWORDS.contains(token)) {
                return Verdict.reject(
                        "SQL 中包含禁止的关键字：" + token + "。本工具是只读的，只能执行 SELECT 查询。");
            }
        }

        // ---------------------------------------------------------------
        // 加固 LIMIT
        //
        // 模型经常忘记写 LIMIT，直接 SELECT * FROM 一张百万行的表。
        // 这里做两件事：没写就补上；写了但太大就收敛到上限。
        // ---------------------------------------------------------------
        String hardened = enforceLimit(sql, masked);
        return Verdict.pass(hardened);
    }

    /**
     * 把字符串字面量 / 引号标识符 / 注释替换成等长空格。
     *
     * 这是一个手写的小状态机。相比正则，它能正确处理：
     *   - '' 形式的转义引号（SQL 标准里用两个单引号表示一个单引号）
     *   - 未闭合的引号（截断输入）
     *   - 注释嵌套引号、引号里包注释
     */
    static String mask(String sql) {
        char[] out = sql.toCharArray();
        int i = 0;
        int n = sql.length();

        while (i < n) {
            char c = sql.charAt(i);

            if (c == '\'') {
                // 单引号字符串字面量
                out[i] = ' ';
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            // 转义的引号，继续留在字符串里
                            out[i] = ' ';
                            out[i + 1] = ' ';
                            i += 2;
                            continue;
                        }
                        out[i] = ' ';
                        i++;
                        break;
                    }
                    out[i] = ' ';
                    i++;
                }

            } else if (c == '"') {
                // 双引号标识符（如 "my""column"）
                out[i] = ' ';
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '"') {
                        out[i] = ' ';
                        i++;
                        break;
                    }
                    out[i] = ' ';
                    i++;
                }

            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                // 行注释直到行尾
                while (i < n && sql.charAt(i) != '\n') {
                    out[i] = ' ';
                    i++;
                }

            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                // 块注释
                out[i] = ' ';
                out[i + 1] = ' ';
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    out[i] = ' ';
                    i++;
                }
                if (i + 1 < n) {
                    out[i] = ' ';
                    out[i + 1] = ' ';
                    i += 2;
                } else {
                    while (i < n) {
                        out[i] = ' ';
                        i++;
                    }
                }

            } else {
                i++;
            }
        }

        return new String(out);
    }

    /** 按非标识符字符切词，全部转大写。 */
    private static List<String> tokenize(String masked) {
        return Pattern.compile("[^A-Za-z0-9_]+")
                .splitAsStream(masked.toUpperCase(Locale.ROOT))
                .filter(t -> !t.isEmpty())
                .toList();
    }

    /**
     * 保证 SQL 带一个不超过 MAX_ROWS 的 LIMIT。
     *
     * 利用掩码串和原串等长的特性，在掩码里定位、在原串上修改。
     */
    private static String enforceLimit(String sql, String masked) {
        Matcher m = LIMIT_PATTERN.matcher(masked);

        if (m.find()) {
            // 已经有 LIMIT 了，把过大的数字收敛下来
            int start = m.start(1);
            int end = m.end(1);
            try {
                long current = Long.parseLong(sql.substring(start, end));
                if (current > MAX_ROWS) {
                    return sql.substring(0, start) + MAX_ROWS + sql.substring(end);
                }
            } catch (NumberFormatException ignored) {
                // 数字长得离谱（超出 long），直接换成上限
                return sql.substring(0, start) + MAX_ROWS + sql.substring(end);
            }
            return sql;
        }

        // FETCH FIRST n ROWS ONLY 也是一种限流语法，尊重它
        if (Pattern.compile("(?i)\\bfetch\\b").matcher(masked).find()) {
            return sql;
        }

        return sql + " LIMIT " + MAX_ROWS;
    }
}
