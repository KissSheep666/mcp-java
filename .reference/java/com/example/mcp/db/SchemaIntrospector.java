package com.example.mcp.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 【工具层·支撑】把数据库结构读出来，喂给模型。
 *
 * 为什么这件事值得单独写一个类？
 *
 * 因为**模型看不见你的库**。你问它"上个月领用最多的备件"，
 * 它不知道有 spare_usage 这张表，不知道字段叫 used_at 而不是 create_time，
 * 不知道 part_id 要 JOIN 到 spare_part。
 *
 * 它唯一的信息来源就是你通过工具给它的描述。
 *
 * 所以 list_tables / describe_table 这两个工具的质量，
 * 直接决定了整个 MCP 好不好用——比 run_query 本身还重要。
 * 模型 SQL 写得对不对，九成取决于它有没有看懂表结构。
 *
 * 这也是为什么 Db.java 里那些 COMMENT ON 不是装饰。
 */
public final class SchemaIntrospector {

    /** 合法标识符。用来防止表名被拼进 SQL 时注入。 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SchemaIntrospector() {
    }

    /**
     * 列出所有表：表名 + 行数 + 中文说明。
     */
    public static String listTables(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();

        List<String[]> tables = new ArrayList<>();   // {name, remark}

        try (ResultSet rs = meta.getTables(null, conn.getSchema(), "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(new String[]{
                        rs.getString("TABLE_NAME"),
                        rs.getString("REMARKS")
                });
            }
        }

        if (tables.isEmpty()) {
            return "当前数据库没有任何表。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(tables.size()).append(" 张表：\n\n");
        sb.append("| 表名 | 行数 | 说明 |\n");
        sb.append("|---|---:|---|\n");

        for (String[] t : tables) {
            sb.append("| ").append(t[0])
              .append(" | ").append(countRows(conn, t[0]))
              .append(" | ").append(blankToDash(t[1]))
              .append(" |\n");
        }

        sb.append("\n用 describe_table 查看某张表的字段结构。");
        return sb.toString();
    }

    /**
     * 描述一张表的字段结构。
     */
    public static String describeTable(Connection conn, String rawTableName) throws SQLException {
        if (rawTableName == null || rawTableName.isBlank()) {
            return "错误：必须提供表名。可用 list_tables 查看有哪些表。";
        }

        String table = rawTableName.strip();

        // 先去元数据里找真实表名，顺便做大小写归一
        // （H2/PostgreSQL 里未加引号的标识符会被转成小写或大写，
        //   模型不知道这个细节，我们替它兜住）
        String actualTable = resolveTableName(conn, table);
        if (actualTable == null) {
            return "错误：找不到表 " + table + "。可用 list_tables 查看有哪些表。";
        }

        DatabaseMetaData meta = conn.getMetaData();

        // 主键列
        Set<String> pkColumns = new LinkedHashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(null, conn.getSchema(), actualTable)) {
            while (rs.next()) {
                pkColumns.add(rs.getString("COLUMN_NAME"));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("表 ").append(actualTable);

        String tableRemark = tableRemark(conn, actualTable);
        if (tableRemark != null && !tableRemark.isBlank()) {
            sb.append(" —— ").append(tableRemark);
        }
        sb.append("\n\n");

        sb.append("| 字段名 | 类型 | 可空 | 主键 | 说明 |\n");
        sb.append("|---|---|---|---|---|\n");

        int count = 0;
        try (ResultSet rs = meta.getColumns(null, conn.getSchema(), actualTable, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                String type = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                String remark = rs.getString("REMARKS");

                // 只给变长类型带长度，INTEGER 这类带上反而干扰阅读
                String typeDisplay = needsLength(type) && size > 0
                        ? type + "(" + size + ")"
                        : type;

                sb.append("| ").append(name)
                  .append(" | ").append(typeDisplay)
                  .append(" | ").append(nullable ? "是" : "否")
                  .append(" | ").append(pkColumns.contains(name) ? "✓" : "")
                  .append(" | ").append(blankToDash(remark))
                  .append(" |\n");
                count++;
            }
        }

        sb.append("\n共 ").append(count).append(" 个字段。");
        if (count > 0 && sb.indexOf("—") < 0) {
            // 提醒：注释缺失会让模型猜字段含义
            sb.append("\n\n提示：本表多数字段缺少注释，模型需要靠字段名推断含义，容易出错。");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 在元数据里找出真实表名，做大小写无关匹配。 */
    private static String resolveTableName(Connection conn, String input) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, conn.getSchema(), "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name.equalsIgnoreCase(input)) {
                    return name;
                }
            }
        }
        return null;
    }

    private static String tableRemark(Connection conn, String table) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, conn.getSchema(), table, new String[]{"TABLE"})) {
            if (rs.next()) {
                return rs.getString("REMARKS");
            }
        }
        return null;
    }

    /**
     * 数一下行数。
     *
     * 注意：这里对每张表做全表 COUNT，表大了会很慢。
     * 生产环境应该改用数据库的统计信息（PostgreSQL 的 pg_class.reltuples、
     * MySQL 的 information_schema.TABLES.TABLE_ROWS），拿到的是近似值但零开销。
     * 演示库小，直接数最准确。
     */
    private static String countRows(Connection conn, String table) {
        // 表名来自元数据而非用户输入，但仍做一次严格校验，
        // 保证拼接进 SQL 的一定是合法标识符
        if (!SAFE_IDENTIFIER.matcher(table).matches()) {
            return "?";
        }
        String sql = "SELECT COUNT(*) FROM \"" + table.toUpperCase(Locale.ROOT) + "\"";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? String.valueOf(rs.getLong(1)) : "?";
        } catch (SQLException e) {
            return "?";
        }
    }

    /** INTEGER 这种定长类型不需要显示长度。 */
    private static boolean needsLength(String type) {
        String t = type.toUpperCase(Locale.ROOT);
        return t.contains("CHAR") || t.contains("BINARY") || t.contains("DECIMAL");
    }

    private static String blankToDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s.strip();
    }
}
