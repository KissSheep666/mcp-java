# Demo 3 - 只读数据库 MCP

> 本篇完整代码见文末 **附录：完整代码**（4 个文件全在）
> 设计说明见 [`只读数据库MCP.md`](./只读数据库MCP.md)
> 涉及文件：`db/Db.java`、`db/SchemaIntrospector.java`、`db/DbMcpServer.java`、`db/DbAgentMain.java`

---

## 一、这个 Demo 要做什么？

前两个 Demo 的工具是玩具（加法、假天气）。这个 Demo 换成一个真实的业务场景：

> **让 AI 能用自然语言查数据库。**

你问：「9 月份领用数量最多的 5 个备件是什么？分别用在哪个部门？」

它自己完成：看有哪些表 → 看表结构 → 写 SQL → 执行 → 组织答案。

### 1.1 暴露 4 个工具


| 工具 | 输入 | 输出 | 作用 |
| --- | --- | --- | --- |
| `list_tables` | 无 | 表名 + 行数 + 中文说明 | 「有什么数据可查」 |
| `describe_table` | 表名 | 字段、类型、可空、主键、**中文注释** | 「这张表什么结构」 |
| `run_query` | SELECT 语句 | Markdown 结果表格 | 主角 |
| `explain_query` | SELECT 语句 | 执行计划 | 防止写出全表扫 |

### 1.2 跑起来是什么样

```
用户: 9 月份领用数量最多的 5 个备件是什么？分别用在哪个部门？

  [调用] list_tables {}
  [调用] describe_table {table_name: spare_usage}
  [调用] describe_table {table_name: spare_part}
  [调用] run_query {sql: SELECT p.code, p.name, SUM(u.quantity) AS total ...}
  [结果] | CODE | NAME | TOTAL |
         |---|---|---|
         | MF-0002 | O型密封圈 | 200 |
         ...

Claude: 9 月份领用最多的是 O型密封圈，共 200 个，主要用于装配车间……
```

**注意中间那三行。** 模型先看有哪些表，再看表结构，最后才写 SQL。

**★ 这个顺序不是我们教的，是工具描述引导出来的。** 这是本 Demo 最重要的一课。

---

## 二、核心原理讲解

### 2.1 ★ 为什么 describe_table 是最重要的工具

先说一个事实：

> **模型看不见你的数据库。**

你问它「上个月领用最多的备件」，它不知道：

- 有 `spare_usage` 这张表
- 字段叫 `used_at` 而不是 `create_time`
- `part_id` 要 JOIN 到 `spare_part`
- `quantity` 是「本次领用数量」还是「剩余数量」

**它唯一的信息来源就是你通过工具给它的描述。**

所以：

```
┌────────────────────────────────────────────────────────────┐
│  run_query 能不能查对，                                    │
│  九成取决于模型有没有看懂表结构。                            │
│                                                            │
│  所以 describe_table 的质量，比 run_query 本身还重要。       │
└────────────────────────────────────────────────────────────┘
```

### 2.2 表结构自省：把中文注释喂给模型

那怎么让模型「看懂」表结构？靠**数据库自己的注释**。

```sql
CREATE TABLE spare_usage (...);

COMMENT ON TABLE  spare_usage        IS '备件领用记录，一行代表一次领用';
COMMENT ON COLUMN spare_usage.used_at IS '领用日期';
COMMENT ON COLUMN spare_usage.dept    IS '领用部门。可能为空，表示未登记';
```

**这些注释不是装饰，是给模型看的。**

`describe_table` 通过 JDBC 标准接口把它们读出来：

```java
DatabaseMetaData meta = conn.getMetaData();

try (ResultSet rs = meta.getColumns(null, conn.getSchema(), actualTable, "%")) {
    while (rs.next()) {
        String name   = rs.getString("COLUMN_NAME");
        String type   = rs.getString("TYPE_NAME");
        String remark = rs.getString("REMARKS");   // ← 就是 COMMENT ON 的内容
        ...
    }
}
```

`REMARKS` 字段就是数据库里 `COMMENT ON COLUMN` 写的内容。

**用 DatabaseMetaData 而不是查 information_schema 的好处：完全跨数据库。**
换 PostgreSQL / MySQL / Oracle 都不用改代码。

**输出给模型长这样：**

```
表 SPARE_USAGE —— 备件领用记录，一行代表一次领用

| 字段名 | 类型 | 可空 | 主键 | 说明 |
|---|---|---|---|---|
| ID | INTEGER | 否 | ✓ | — |
| PART_ID | INTEGER | 否 |  | 关联 spare_part.id |
| QUANTITY | INTEGER | 否 |  | 本次领用数量 |
| DEPT | CHARACTER VARYING(32) | 是 |  | 领用部门。可能为空，表示未登记 |
| OPERATOR | CHARACTER VARYING(32) | 是 |  | 领用人 |
| USED_AT | DATE | 否 |  | 领用日期 |
```

**对比一下没有注释的情况：**

```
| DEPT | CHARACTER VARYING(32) | 是 |  | — |
```

模型只能靠字段名猜 `DEPT` 是什么。它可能猜对，也可能猜错。

> 💡 **实操建议**：对接真实库时，如果表字段没有注释，
> **先去补注释，效果比调 prompt 明显得多。**

**一个容易忽略的细节：为什么输出用 Markdown 表格而不是 JSON？**

因为模型读表格比读 JSON 更准，而且 token 更省（不用重复字段名）。

### 2.3 ★ 工具描述工程

这是本 Demo 最「反直觉」的一课。

看这两句描述：

```java
// list_tables
.description("列出数据库中所有的表，包含表名、行数和中文说明。"
        + "在写任何查询之前，如果不确定有哪些表可用，先调用这个工具。")

// describe_table
.description("查看某张表的完整字段结构，包含字段名、类型、是否可空、主键标记和中文注释。"
        + "写查询前如果不确定字段名或字段含义，必须先调用这个工具——"
        + "不要凭猜测写字段名。")
```

**正是这两句话让模型形成了「先看表 → 再看结构 → 最后写 SQL」的习惯。**

如果没有这两句，模型会直接凭字段名的直觉写 SQL，然后因为表名/字段名不对而失败。

**写描述的公式：**

```
description = 「做什么」 + 「什么时候用」
```

对比一下：

| 写法 | 效果 |
| --- | --- |
| `"数据库工具"` | ❌ 模型只能瞎猜 |
| `"查询数据库"` | ⚠️ 知道做什么，不知道什么时候用 |
| `"执行一条只读 SELECT 查询。只允许 SELECT，任何写操作都会被拒绝。结果最多返回 200 行。建议：先用 list_tables 和 describe_table 确认表结构，再写查询。"` | ✅ 完整 |

**记住一句话：**

> **`description` 是模型判断「要不要用这个工具」的唯一依据。**
> 它看不到你的代码，只有这一句话。

这是我在搭这个 Demo 时改了好几版才稳定下来的地方。

### 2.4 分层架构：为什么换库只改一个文件

```
┌──────────────────────────────────────────────────────┐
│  DbMcpServer.java      工具定义 + handler             │  ← 跟具体是什么库无关
├──────────────────────────────────────────────────────┤
│  SqlGuard.java          SQL 白名单 / 强制 LIMIT        │  ← 跟具体是什么库无关
│  AuditLog.java          审计日志                       │
│  SchemaIntrospector     表结构自省                     │
├──────────────────────────────────────────────────────┤
│  Db.java               数据源 + 演示数据               │  ← ★只有这层要换
└──────────────────────────────────────────────────────┘
```

**换库只改 `Db.java`。** 上面两层一行都不用动。

而且 `Db.java` 已经做好了环境变量支持：

```bash
export DB_URL=jdbc:postgresql://test-db:5432/sparedb
export DB_USER=mcp_readonly
export DB_PASSWORD=******
```

设了 `DB_URL` 就自动切到外部库模式，**代码一行不用改**。

**一个类比：**

```
Db.java              = 电源适配器（不同的墙插换不同的适配器）
上面三层             = 电器本身（插上就能用，不关心电从哪来）
```

---

## 三、完整代码实现

这一节把本篇涉及的四个文件**完整列出**。顺序和依赖方向一致：先数据源，再自省，再工具，最后入口。

### 3.1 `Db.java` —— 数据源层

**这个文件是整套架构里唯一需要改的一层**，换成公司真实的库只动它（或者直接配 `DB_URL` 环境变量，一行都不用改）。

开头那个「只读 URL」的注释值得仔细看——那是一个真实踩过的坑：

```java
// 管理连接：带 DB_CLOSE_DELAY=-1，让内存库在整个 JVM 生命周期内存活
private static final String ADMIN_URL = "jdbc:h2:mem:sparedb;DB_CLOSE_DELAY=-1";

// ★ 只读连接：注意这里【没有】DB_CLOSE_DELAY
private static final String READONLY_URL = isDemoMode() ? "jdbc:h2:mem:sparedb" : ENV_URL;
```

**为什么不能复用同一个 URL？** 因为 H2 里 `SET DB_CLOSE_DELAY` 需要管理员权限。
只读账号的连接串里带上它，连接会直接失败：

```
Admin rights are required for this operation; SQL statement: SET DB_CLOSE_DELAY -1
```

它是**数据库级**设置，由管理员连接设好之后就一直有效，后续连接不需要（也不能）再设一遍。
**我第一版就是把 URL 抽成一个常量复用的，结果只读连接全部失败。**

另外注意 `createLargeTable()` 里那张 5000 行的 `device_log`。**它不是凑数的**——
2.2 节讲的「小数据量下机制是隐形的」，就是靠它才能验证。

```java
package com.example.mcp.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 【数据源层】—— 整个工程里唯一需要改动的层。
 *
 * ══════════════════════════════════════════════════════════════════
 *  ★ 铁律：先接测试库，永远不要直接接生产库
 * ══════════════════════════════════════════════════════════════════
 *
 * 即使你做了只读账号 + SQL 白名单 + 超时，第一次跑也请接测试库。
 * 理由不是"怕它改数据"（只读账号已经挡住了），而是：
 *
 *   1. 你不知道模型会写出什么查询。一条没加索引的 JOIN
 *      在千万行表上能把生产库拖垮——只读不代表无害。
 *   2. 你不知道模型会查出什么数据。它可能把客户手机号、
 *      工资、身份证号直接返回并写进对话历史。
 *   3. 你需要先看清楚"工具描述写得够不够清楚"，
 *      这只有在真实结构上跑一遍才知道。
 *
 * ──────────────────────────────────────────────────────────────────
 *  两种模式
 * ──────────────────────────────────────────────────────────────────
 *
 * 【演示模式】（默认）
 *   用内置的 H2 内存库，自带一套贴近真实的测试数据。
 *   每次启动重新建，改坏了重启即可，零副作用。
 *   直接跑，不需要任何配置。
 *
 * 【接真实测试库】
 *   设置环境变量即可，代码一行不用改：
 *
 *     DB_URL=jdbc:postgresql://test-db:5432/sparedb
 *     DB_USER=mcp_readonly
 *     DB_PASSWORD=******
 *
 *   设了 DB_URL 就自动进入这个模式，不会再建演示数据。
 *   这个账号必须是【只读】的——这不是可选项。
 */
public final class Db {

    // ------------------------------------------------------------------
    // 连接配置：优先读环境变量，没有才用内置演示库
    // ------------------------------------------------------------------

    private static final String ENV_URL = System.getenv("DB_URL");

    /** 是否在用内置演示库。 */
    public static boolean isDemoMode() {
        return ENV_URL == null || ENV_URL.isBlank();
    }

    /**
     * 管理连接用的 URL —— 只在演示模式下用于建表灌数据。
     *
     * DB_CLOSE_DELAY=-1 让内存库在整个 JVM 生命周期内存活。
     * 不加这个，最后一个连接关闭时库就没了。
     */
    private static final String ADMIN_URL = "jdbc:h2:mem:sparedb;DB_CLOSE_DELAY=-1";
    private static final String ADMIN_USER = "sa";
    private static final String ADMIN_PASSWORD = "";

    /**
     * ★ 只读连接。
     *
     * 在演示模式下指向内置库的只读账号；
     * 在生产对接时指向你用环境变量配的测试库。
     *
     * 注意演示模式的 URL 里【没有】DB_CLOSE_DELAY —— 这是个真踩过的坑：
     * H2 里 SET DB_CLOSE_DELAY 需要管理员权限，只读账号带上它会直接连接失败：
     *   "Admin rights are required for this operation: SET DB_CLOSE_DELAY -1"
     * 它是数据库级设置，由 init() 里的管理员连接设好后就一直有效。
     */
    private static final String READONLY_URL =
            isDemoMode() ? "jdbc:h2:mem:sparedb" : ENV_URL;

    private static final String READONLY_USER =
            System.getenv().getOrDefault("DB_USER", "app_readonly");

    private static final String READONLY_PASSWORD =
            System.getenv().getOrDefault("DB_PASSWORD", "readonly_pwd");

    private Db() {
    }

    /** 查询专用连接。永远走只读账号。 */
    public static Connection readOnlyConnection() throws SQLException {
        return DriverManager.getConnection(READONLY_URL, READONLY_USER, READONLY_PASSWORD);
    }

    /** 演示库的说明，用于自检输出。 */
    public static String describeTarget() {
        return isDemoMode()
                ? "内置演示库 jdbc:h2:mem:sparedb（测试数据，可随意折腾）"
                : "外部测试库 " + ENV_URL + "（用户 " + READONLY_USER + "）";
    }

    // ==================================================================
    // 以下全部只在演示模式下执行
    // ==================================================================

    /**
     * 建立演示库：建表、写注释、灌测试数据、创建只读账号。
     *
     * 接真实测试库时不会走到这里。
     */
    public static void init() {
        if (!isDemoMode()) {
            // 外部库：只验证能连上、账号是通的，不碰任何数据
            try (Connection conn = readOnlyConnection()) {
                if (!conn.isValid(5)) {
                    throw new IllegalStateException("外部测试库连接无效");
                }
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "连不上外部测试库 " + ENV_URL + "，请检查 DB_URL / DB_USER / DB_PASSWORD", e);
            }
            return;
        }

        try (Connection conn = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PASSWORD);
             Statement st = conn.createStatement()) {

            createBusinessTables(st);
            createLargeTable(st);
            seedBusinessData(st);
            createReadOnlyAccount(st);

        } catch (SQLException e) {
            throw new IllegalStateException("初始化演示库失败", e);
        }
    }

    /** 业务表 —— 模拟一套备件管理系统。 */
    private static void createBusinessTables(Statement st) throws SQLException {
        // --------------------------------------------------------------
        // 注意 COMMENT ON：这些中文注释不是装饰。
        // describe_table 会把它们读出来给模型看——
        // 模型不知道 spare_code 是什么，但它能看懂"物料编码，业务唯一键"。
        //
        // 这是让 AI 查库真正好用的关键。对接真实库时，
        // 如果表字段没有注释，先去补注释，效果比调 prompt 明显得多。
        // --------------------------------------------------------------
        st.execute("""
                CREATE TABLE IF NOT EXISTS spare_part (
                    id       INT PRIMARY KEY,
                    code     VARCHAR(32)  NOT NULL,
                    name     VARCHAR(64)  NOT NULL,
                    spec     VARCHAR(64),
                    unit     VARCHAR(16),
                    category VARCHAR(32)
                )
                """);
        st.execute("COMMENT ON TABLE spare_part IS '备件物料主数据'");
        st.execute("COMMENT ON COLUMN spare_part.id IS '主键'");
        st.execute("COMMENT ON COLUMN spare_part.code IS '物料编码，业务唯一键，如 BJ-0001'");
        st.execute("COMMENT ON COLUMN spare_part.name IS '物料名称'");
        st.execute("COMMENT ON COLUMN spare_part.spec IS '规格型号'");
        st.execute("COMMENT ON COLUMN spare_part.unit IS '计量单位'");
        st.execute("COMMENT ON COLUMN spare_part.category IS '物料分类，如 轴承/密封件/电气'");

        st.execute("""
                CREATE TABLE IF NOT EXISTS spare_stock (
                    id         INT PRIMARY KEY,
                    part_id    INT NOT NULL,
                    warehouse  VARCHAR(32),
                    quantity   INT NOT NULL,
                    updated_at DATE
                )
                """);
        st.execute("COMMENT ON TABLE spare_stock IS '备件库存快照，每个物料在每个仓库一条'");
        st.execute("COMMENT ON COLUMN spare_stock.part_id IS '关联 spare_part.id'");
        st.execute("COMMENT ON COLUMN spare_stock.warehouse IS '仓库名称'");
        st.execute("COMMENT ON COLUMN spare_stock.quantity IS '当前库存数量，可为负表示待补货'");
        st.execute("COMMENT ON COLUMN spare_stock.updated_at IS '快照日期'");

        st.execute("""
                CREATE TABLE IF NOT EXISTS spare_usage (
                    id       INT PRIMARY KEY,
                    part_id  INT NOT NULL,
                    quantity INT NOT NULL,
                    dept     VARCHAR(32),
                    operator VARCHAR(32),
                    used_at  DATE NOT NULL
                )
                """);
        st.execute("COMMENT ON TABLE spare_usage IS '备件领用记录，一行代表一次领用'");
        st.execute("COMMENT ON COLUMN spare_usage.part_id IS '关联 spare_part.id'");
        st.execute("COMMENT ON COLUMN spare_usage.quantity IS '本次领用数量'");
        st.execute("COMMENT ON COLUMN spare_usage.dept IS '领用部门。可能为空，表示未登记'");
        st.execute("COMMENT ON COLUMN spare_usage.operator IS '领用人'");
        st.execute("COMMENT ON COLUMN spare_usage.used_at IS '领用日期'");

        // 故意带 NULL 的表：用来验证结果格式化不会把 NULL 显示成 "null" 字符串，
        // 也验证模型能正确理解"这个字段可能为空"
        st.execute("""
                CREATE TABLE IF NOT EXISTS spare_supplier (
                    id           INT PRIMARY KEY,
                    part_id      INT NOT NULL,
                    supplier     VARCHAR(64),
                    contact      VARCHAR(32),
                    phone        VARCHAR(32),
                    lead_time    INT,
                    last_order   DATE
                )
                """);
        st.execute("COMMENT ON TABLE spare_supplier IS '备件供应商信息。部分字段可能未登记，查询时注意 NULL'");
        st.execute("COMMENT ON COLUMN spare_supplier.part_id IS '关联 spare_part.id'");
        st.execute("COMMENT ON COLUMN spare_supplier.supplier IS '供应商名称'");
        st.execute("COMMENT ON COLUMN spare_supplier.contact IS '联系人，可能未登记（NULL）'");
        st.execute("COMMENT ON COLUMN spare_supplier.phone IS '联系电话，可能未登记（NULL）'");
        st.execute("COMMENT ON COLUMN spare_supplier.lead_time IS '交货周期（天），可能未登记（NULL）'");
        st.execute("COMMENT ON COLUMN spare_supplier.last_order IS '最近一次下单日期'");
    }

    /**
     * 一张大表 —— 这是测试库里【最该有】的东西。
     *
     * 为什么？因为只有它才能验证：
     *   · SqlGuard 的 LIMIT 强制追加到底有没有生效
     *   · McpAgent 的工具结果长度上限到底有没有触发
     *   · 模型面对 200 行返回时会不会自己加聚合条件
     *
     * 小数据量下这些机制全都是隐形的，你根本不知道它们有没有工作。
     * 5000 行是刻意选的：够大到超限，又不至于让演示变慢。
     */
    private static void createLargeTable(Statement st) throws SQLException {
        st.execute("""
                CREATE TABLE IF NOT EXISTS device_log (
                    id          INT PRIMARY KEY,
                    device_code VARCHAR(32) NOT NULL,
                    level       VARCHAR(8)  NOT NULL,
                    message     VARCHAR(200),
                    logged_at   TIMESTAMP
                )
                """);
        st.execute("COMMENT ON TABLE device_log IS '设备运行日志，数据量较大（约 5000 行），查询务必带条件和聚合'");
        st.execute("COMMENT ON COLUMN device_log.device_code IS '设备编号'");
        st.execute("COMMENT ON COLUMN device_log.level IS '日志级别：INFO / WARN / ERROR'");
        st.execute("COMMENT ON COLUMN device_log.message IS '日志内容'");
        st.execute("COMMENT ON COLUMN device_log.logged_at IS '记录时间，精确到分钟'");

        // 用 SYSTEM_RANGE 批量生成，比拼 5000 条 INSERT 快得多
        st.execute("""
                INSERT INTO device_log (id, device_code, level, message, logged_at)
                SELECT
                    X,
                    'DEV-' || LPAD(CAST(X AS VARCHAR), 4, '0'),
                    CASE
                        WHEN MOD(X, 97) = 0 THEN 'ERROR'
                        WHEN MOD(X, 11) = 0 THEN 'WARN'
                        ELSE 'INFO'
                    END,
                    '设备事件记录 #' || X,
                    DATEADD('MINUTE', X, TIMESTAMP '2026-09-01 00:00:00')
                FROM SYSTEM_RANGE(1, 5000)
                """);
    }

    /** 业务数据。 */
    private static void seedBusinessData(Statement st) throws SQLException {
        st.execute("""
                INSERT INTO spare_part (id, code, name, spec, unit, category) VALUES
                  (1, 'BJ-0001', '深沟球轴承',   '6205-2RS',    '个', '轴承'),
                  (2, 'BJ-0002', '圆锥滚子轴承', '30206',       '个', '轴承'),
                  (3, 'MF-0001', '骨架油封',     'TC 35x52x7',  '个', '密封件'),
                  (4, 'MF-0002', 'O型密封圈',    'NBR 40x3',    '个', '密封件'),
                  (5, 'DQ-0001', '交流接触器',   'CJX2-2510',   '个', '电气'),
                  (6, 'DQ-0002', '断路器',       'DZ47-63 C16', '个', '电气'),
                  (7, 'DQ-0003', '变频器',       'MD500-4T2.2G','台', '电气'),
                  (8, 'YY-0001', '液压油',       'L-HM46 18L',  '桶', '油液'),
                  (9, 'YY-0002', '润滑脂',       '锂基 2# 1kg', '罐', '油液'),
                  (10,'LQ-0001', '空气滤芯',     'AF-26500',    '个', '滤材')
                """);

        st.execute("""
                INSERT INTO spare_stock (id, part_id, warehouse, quantity, updated_at) VALUES
                  (1, 1, '一号库', 120, DATE '2026-10-01'),
                  (2, 1, '二号库',  45, DATE '2026-10-01'),
                  (3, 2, '一号库',  30, DATE '2026-10-01'),
                  (4, 3, '一号库', 400, DATE '2026-10-01'),
                  (5, 4, '二号库', 850, DATE '2026-10-01'),
                  (6, 5, '一号库',  18, DATE '2026-10-01'),
                  (7, 6, '二号库',  22, DATE '2026-10-01'),
                  (8, 7, '一号库',   3, DATE '2026-10-01'),
                  (9, 8, '三号库',  60, DATE '2026-10-01'),
                  (10,9, '三号库',  75, DATE '2026-10-01'),
                  (11,10,'二号库', 200, DATE '2026-10-01')
                """);

        // 8 月和 9 月各一批，方便演示"按月统计"。
        // 第 7 行故意把 dept 设为 NULL，用来测脏数据。
        st.execute("""
                INSERT INTO spare_usage (id, part_id, quantity, dept, operator, used_at) VALUES
                  (1,  1, 12, '机修车间',   '张伟', DATE '2026-08-05'),
                  (2,  3, 60, '机修车间',   '张伟', DATE '2026-08-07'),
                  (3,  5,  4, '电气车间',   '李娜', DATE '2026-08-11'),
                  (4,  1,  8, '二号生产线', '王强', DATE '2026-08-19'),
                  (5,  8,  6, '机修车间',   '张伟', DATE '2026-08-25'),
                  (6,  4,150, '装配车间',   '赵敏', DATE '2026-08-28'),
                  (7,  1, 20, NULL,         '王强', DATE '2026-09-03'),
                  (8,  3, 90, '装配车间',   '赵敏', DATE '2026-09-06'),
                  (9,  5,  6, '电气车间',   '李娜', DATE '2026-09-09'),
                  (10, 7,  1, '电气车间',   '李娜', DATE '2026-09-12'),
                  (11, 1, 15, '二号生产线', '王强', DATE '2026-09-18'),
                  (12,10, 40, '机修车间',   '张伟', DATE '2026-09-21'),
                  (13, 6,  3, '电气车间',   '李娜', DATE '2026-09-24'),
                  (14, 4,200, '装配车间',   '赵敏', DATE '2026-09-27'),
                  (15, 2,  5, '机修车间',   '张伟', DATE '2026-09-29')
                """);

        st.execute("""
                INSERT INTO spare_supplier (id, part_id, supplier, contact, phone, lead_time, last_order) VALUES
                  (1,  1, '哈轴集团',       '刘经理', '0451-88880001',  15, DATE '2026-09-10'),
                  (2,  2, '哈轴集团',       '刘经理', '0451-88880001',  15, DATE '2026-08-22'),
                  (3,  3, '宁波密封件厂',   NULL,     NULL,             30, DATE '2026-07-15'),
                  (4,  4, '宁波密封件厂',   NULL,     NULL,             30, DATE '2026-09-01'),
                  (5,  5, '正泰电器',       '陈工',   '0577-66660002',   7, DATE '2026-09-20'),
                  (6,  6, '正泰电器',       '陈工',   '0577-66660002',   7, DATE '2026-09-20'),
                  (7,  7, '汇川技术',       '周工',   NULL,             45, DATE '2026-06-30'),
                  (8,  8, '长城润滑油',     NULL,     NULL,             NULL, NULL),
                  (9,  9, '长城润滑油',     NULL,     NULL,             NULL, NULL),
                  (10,10, '曼牌滤清器',     '吴经理', '021-55550003',   20, DATE '2026-09-15')
                """);
    }

    /**
     * ★ 创建只读账号。
     *
     * 这是整套设计里最关键的一步，也是最容易被跳过的一步。
     *
     * 有了它，即使 SqlGuard 被绕过、即使模型被提示注入操纵生成了
     * DELETE FROM spare_part，数据库也会直接拒绝。
     *
     * 接真实测试库时对应的是：找 DBA 申请只有 SELECT 权限的账号。
     * 别用应用账号凑合——那等于把这道墙拆了。
     */
    private static void createReadOnlyAccount(Statement st) throws SQLException {
        st.execute("CREATE USER IF NOT EXISTS " + READONLY_USER
                + " PASSWORD '" + READONLY_PASSWORD + "'");
        st.execute("GRANT SELECT ON SCHEMA PUBLIC TO " + READONLY_USER);
    }
}
```

### 3.2 `SchemaIntrospector.java` —— 表结构自省

把数据库的中文注释读出来喂给模型。核心是 JDBC 的 `DatabaseMetaData`，
不写一行 `information_schema` 查询——**完全跨数据库**。

两个必须处理的细节：

- **大小写归一**：H2/PostgreSQL 里未加引号的标识符会被转成大写，模型不知道这个细节（它会传 `spare_usage`），要替它兜住。找不到时返回**友好错误 + 引导**，而不是抛异常
- **类型长度只给变长类型加**：`INTEGER` 这类定长类型带上长度反而干扰阅读

```java
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
```

### 3.3 `DbMcpServer.java` —— MCP Server 主体

4 个工具的定义和 handler 都在这里。**工具描述的措辞直接决定模型行为**（2.3 节），对照着看。

`run_query` 的 handler 是整个项目的核心，三道墙依次经过：

```java
// ---- 第一关：安全层 ----
SqlGuard.Verdict verdict = SqlGuard.inspect(sql);
if (!verdict.ok()) {
    AuditLog.record("run_query", sql, false, verdict.reason(), ms(t0));  // 被拒绝也要记
    return error("SQL 被拒绝：" + verdict.reason());
}

// ---- 第二关：执行 ----
try (Connection conn = Db.readOnlyConnection();      // 永远用只读连接
     Statement st = conn.createStatement()) {
    st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);       // 第三关：超时
    try (ResultSet rs = st.executeQuery(verdict.sql())) {   // 用加固后的 SQL
        ...
    }
}
```

三个要点：执行时用 `verdict.sql()`（加固过的）· 审计记录**原始** sql（回溯要看模型原本想干什么）· 数据库报错原样回传（模型自己能改对）。

```java
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
```

### 3.4 `DbAgentMain.java` —— 入口

带三种模式：`check`（自检，不需要 API Key）· 单次提问 · `chat`（交互）。

注意传参用的是 `-DappArgs=`，不是 `-Dexec.args=`——原因见第一节的坑 ④。

```java
package com.example.mcp.db;

import com.example.mcp.core.McpAgent;

import java.util.Arrays;
import java.util.Scanner;

/**
 * 把「只读数据库 MCP」接到 Claude 上——完整可用的自然语言查库。
 *
 * 自检（不需要 API Key，先确认 MCP 侧接好了）：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.db.DbAgentMain -DappArgs=check
 *
 * 默认问题（需要 ANTHROPIC_API_KEY）：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.db.DbAgentMain
 *
 * 自定义问题：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.db.DbAgentMain \
 *       -DappArgs="9 月份领用数量最多的 5 个备件是什么"
 *
 * 交互模式（连接一次，连续提问）：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.db.DbAgentMain -DappArgs=chat
 */
public final class DbAgentMain {

    /** 指向我们的数据库 MCP Server。换 server 只改这一行。 */
    private static final String SERVER = "com.example.mcp.db.DbMcpServer";

    private static final String DEFAULT_QUESTION =
            "9 月份领用数量最多的 5 个备件是什么？分别用在哪个部门？";

    public static void main(String[] args) {
        // pom 里的 ${appArgs} 为空时会传进来一个空字符串参数，先过滤掉，
        // 否则它会被当成"问题为空"而不是"没带参数"
        String[] effective = Arrays.stream(args)
                .filter(a -> !a.isBlank())
                .toArray(String[]::new);

        if (effective.length == 1 && effective[0].equals("check")) {
            dryRun();
            return;
        }

        if (effective.length == 1 && effective[0].equals("chat")) {
            runInteractive();
            return;
        }

        String question = effective.length > 0
                ? String.join(" ", effective)
                : DEFAULT_QUESTION;

        // try-with-resources 保证 MCP server 子进程被正确终止
        try (McpAgent agent = McpAgent.connect(SERVER)) {
            agent.ask(question);
        }
    }

    /**
     * 自检：只连 MCP，不调用模型。
     *
     * 建议第一次跑的时候先用这个。它能验证：
     *   - server 子进程能起来
     *   - 握手成功、工具清单能拿到
     *   - 工具 schema 能转成 Claude 格式而不报错
     *
     * 这些都不需要 API Key。确认没问题了再去跑真正的提问，
     * 免得把一个配置问题误判成"模型不好用"。
     */
    private static void dryRun() {
        System.out.println("目标数据源: " + Db.describeTarget());
        try (McpAgent agent = McpAgent.connect(SERVER)) {
            System.out.println();
            System.out.println("✅ 自检通过：MCP Server 连接正常，工具已全部加载。");
            System.out.println();
            System.out.println("下一步（需要 ANTHROPIC_API_KEY）：");
            System.out.println("  mvn -q exec:exec -DmainClass=" + DbAgentMain.class.getName());
            System.out.println("  mvn -q exec:exec -DmainClass=" + DbAgentMain.class.getName() + " -DappArgs=chat");
        }
    }

    /**
     * 交互模式。
     *
     * 注意这里只建立一次 MCP 连接，所有问题复用同一个 agent。
     * 现实中的对话产品都是这么做的——每次提问都重启一个 server 进程太浪费。
     *
     * 但要注意：ask() 每次都是从空的对话历史开始，
     * 所以它没有跨轮记忆。要做多轮对话，需要把 messages 提升为 agent 的字段。
     * （这是个很好的练习——自己动手改改看。）
     */
    private static void runInteractive() {
        try (McpAgent agent = McpAgent.connect(SERVER);
             Scanner scanner = new Scanner(System.in, "UTF-8")) {

            System.out.println();
            System.out.println("输入问题，回车执行。输入 exit 退出。");
            System.out.println();

            while (true) {
                System.out.print("> ");
                System.out.flush();

                if (!scanner.hasNextLine()) {
                    break;
                }
                String line = scanner.nextLine().strip();

                if (line.isEmpty()) {
                    continue;
                }
                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    break;
                }

                agent.ask(line);
                System.out.println();
            }
        }
    }
}
```

---

## 四、测试与验证

### 4.1 自检（不需要 API Key）

```bash
mvn -q compile exec:exec -DmainClass=com.example.mcp.db.DbAgentMain -DappArgs=check
```

**预期输出：**

```
目标数据源: 内置演示库 jdbc:h2:mem:sparedb（测试数据，可随意折腾）
已连接 spare-db，加载 4 个工具：[list_tables, describe_table, run_query, explain_query]

✅ 自检通过：MCP Server 连接正常，工具已全部加载。
```

**建议第一次跑的时候先用这个。** 它验证的是 MCP 侧，不需要花 API 的钱。
免得把一个配置问题误判成「模型不好用」。

### 4.2 实际提问（需要 API Key）

```bash
set ANTHROPIC_API_KEY=sk-ant-...
mvn -q compile exec:exec -DmainClass=com.example.mcp.db.DbAgentMain
```

### 4.3 交互模式

```bash
mvn -q compile exec:exec -DmainClass=com.example.mcp.db.DbAgentMain -DappArgs=chat
```

一次连接，连续提问。注意 `ask()` 每次从空历史开始，所以**没有跨轮记忆**——
要做多轮对话需要把 `messages` 提升为 `McpAgent` 的字段（留给你自己改）。

---

## 五、关键知识点总结

### 5.1 一句话回顾整个 Demo

> **把工具从「玩具」换成「真实业务」之后，最重要的事变成了
> 「让模型看懂你的数据结构」——而这件事靠的是表注释和工具描述的质量。**

### 5.2 工具描述写法速查


| 场景 | 写法 |
| --- | --- |
| 有前置依赖 | 在描述里指明顺序：「如果……请先调用 X」 |
| 有硬约束 | 明确写出来：「只允许 SELECT」「最多返回 200 行」 |
| 参数有歧义 | 在参数 description 里解释：「表名，可先用 list_tables 查看有哪些表」 |
| 想防某种错误 | 直接说不要做什么：「**不要凭猜测写字段名**」 |

### 5.3 面试常见问题

**Q1：让 AI 查数据库，最难的地方是什么？**

不是 SQL 生成，是**让模型理解数据结构**。

模型看不见你的库。它不知道有哪些表、字段叫什么、某个字段的业务含义是什么。
这些信息只能通过工具描述和表注释传递。

所以 `describe_table` 这类「元信息工具」的质量，
比 `run_query` 本身更影响最终效果。

**Q2：怎么让模型少写错 SQL？**

三个层次，按性价比排序：

1. **补全数据库表注释**（性价比最高）—— 模型能看懂「物料编码，业务唯一键」
2. **写好工具描述** —— 用「做什么 + 什么时候用」的公式，并明确禁止猜测
3. **把数据库报错原样回传给模型** —— 它看到 `Table not found` 通常能自己改对

**Q3：为什么要限制返回行数？**

两个原因：

- **内存/性能**：`SELECT * FROM` 一张百万行的表会把内存打爆
- **Token 成本**：工具结果会进入对话历史，而**对话历史每一轮都要完整重发**。
  一个 200 行的表格塞进去，之后每一轮都在为它付 input token

第二条更隐蔽。这是为什么 `McpAgent` 里还有个
`MAX_TOOL_RESULT_CHARS = 4000` 的二次截断——即使 SQL 层限了 200 行，
200 行文本也可能很长。

**Q4：为什么输出用 Markdown 表格而不是 JSON？**

模型读表格比读 JSON 更准，而且 token 更省（不用重复字段名）。

**Q5：测试数据为什么要故意造一个 5000 行的大表？**

为了验证限流机制真的生效。

小数据量下这些机制是**隐形**的——你测 15 行的表返回 15 行，
没法判断是「表里就 15 行」还是「LIMIT 生效了」。
测 5000 行的表返回 200 行 + 警告，才是有效验证。

**Q6：这个架构里，换成公司真实的库要改哪些地方？**

只改 `Db.java` 一个文件，而且如果之前配了 `DB_URL` 环境变量，**一行都不用改**。

因为工具层和安全层跟「具体是什么库」无关——
它们只依赖 JDBC 标准接口（`DatabaseMetaData`）和一份 SQL 白名单。

**⚠️ 但真正的答案是：还需要先找 DBA 申请一个只读账号。**
这比改代码重要得多，下一节会讲。

---

**下一节：** [Demo 4 - 安全与加固](./05-Demo4-安全与加固.md)


