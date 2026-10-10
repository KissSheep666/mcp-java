package com.example.mcp.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 验证【第一道墙】：数据库只读账号。
 *
 * ══════════════════════════════════════════════════════════════════
 *  这个类为什么必须单独存在
 * ══════════════════════════════════════════════════════════════════
 *
 * 因为它演示的东西，是整个安全设计里唯一不可替代的一环。
 *
 * SqlGuard（第二道墙）能挡住模型"不小心"写出的危险 SQL，
 * 但它有两个绕不过去的问题：
 *
 *   1. 手写的 SQL 解析器不可能完备。SQL 方言、嵌套注释、
 *      数据库特有语法……总有绕过路径。
 *   2. 它拦不住"绕过 Java 代码"的攻击。谁能直连数据库，
 *      SqlGuard 就形同虚设。
 *
 * 而只读账号是**数据库自己 enforced** 的：
 * 不管你的代码写成什么样，不管谁在连，DELETE 就是执行不了。
 *
 * 这里刻意绕开全部业务代码，直接用只读账号连库执行写操作。
 * 全部被拒绝 = 这道墙有效。
 * ══════════════════════════════════════════════════════════════════
 *
 * 运行：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.db.ReadOnlyAccountDemo
 */
public final class ReadOnlyAccountDemo {

    public static void main(String[] args) {
        // 在【本进程】里建库——H2 内存库是 per-JVM 的，
        // 所以必须自己 init 一份，不能用 server 子进程那个。
        Db.init();

        System.out.println("已用只读账号 app_readonly 建立连接，尝试执行写操作：");
        System.out.println("（这些操作全部绕过了 SqlGuard，直接打到数据库）");
        System.out.println();

        // 写操作 —— 应该全部被数据库拒绝
        expectDenied("DELETE FROM spare_part");
        expectDenied("DROP TABLE spare_part");
        expectDenied("TRUNCATE TABLE spare_usage");
        expectDenied("INSERT INTO spare_part (id, code, name) VALUES (999, 'X', 'X')");
        expectDenied("UPDATE spare_stock SET quantity = 0");

        System.out.println();
        System.out.println("对照组 —— 读操作应该正常：");
        System.out.println();

        // 读操作 —— 应该正常
        expectAllowed("SELECT COUNT(*) FROM spare_part");
        expectAllowed("SELECT COUNT(*) FROM spare_usage");

        System.out.println();
        System.out.println("结论：即使第二道墙（SqlGuard）被完全绕过，");
        System.out.println("      数据依然改不掉。这就是为什么只读账号不能省。");
    }

    private static void expectDenied(String sql) {
        try (Connection conn = Db.readOnlyConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
            System.out.printf("%-4s %-52s → !!! 竟然成功了%n", "❌", sql);
        } catch (SQLException e) {
            System.out.printf("%-4s %-52s → %s%n", "✅", sql, brief(e));
        }
    }

    private static void expectAllowed(String sql) {
        try (Connection conn = Db.readOnlyConnection();
             Statement st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            rs.next();
            System.out.printf("%-4s %-52s → %s%n", "✅", sql, rs.getString(1) + " 行");
        } catch (SQLException e) {
            System.out.printf("%-4s %-52s → !!! 被误伤: %s%n", "❌", sql, brief(e));
        }
    }

    /** 数据库的错误信息很长，截到第一行就够说明问题了。 */
    private static String brief(SQLException e) {
        String msg = e.getMessage();
        if (msg == null) {
            return e.getClass().getSimpleName();
        }
        int nl = msg.indexOf('\n');
        return nl > 0 ? msg.substring(0, nl) : msg;
    }
}
