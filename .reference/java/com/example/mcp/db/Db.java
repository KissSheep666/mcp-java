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
