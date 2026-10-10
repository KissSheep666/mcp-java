# MCP Java 练习工程

> `src/` 里的 Java 文件**全是空的**。代码在文档里，你自己敲。

---

## 文件在哪

```
mcp-java-tutorial\
├── README.md                      ← 你在这里
├── pom.xml                        ← 依赖已配好，不用动
├── docs\
│   ├── 01-概述与环境.md            ← ★ 从这里开始
│   ├── 02-Demo1-最小MCP链路.md     ← 手写 Server + Client（无 LLM）
│   ├── 03-Demo2-接上LLM.md         ← 桥接层 + agent loop
│   ├── 04-Demo3-只读数据库MCP.md   ← 4 个工具 + 表结构自省
│   ├── 05-Demo4-安全与加固.md      ← ★ 三道墙
│   ├── 06-项目整合与面试.md        ← 简历 + 面试话术
│   │
│   ├── 全量代码.md                 ← ★ 全部代码，照着敲
│   ├── MCP-Java-从零到跑通.md      ← 技术详解手册（更深入，当参考书用）
│   └── 只读数据库MCP.md            ← Demo 3/4 的设计说明
├── src\
│   ├── main\java\...\             ← 14 个空文件，等你去填
│   └── main\resources\
│       └── simplelogger.properties ← 日志配置，已写好，不用动
├── .reference\java\               ← 完整实现（卡的实在不行了再看）
└── gen-fullcode-doc.sh            ← 从 src/ 重新生成全量文档的脚本
```

**目录结构已经建好了**，类名和包名跟文档里对得上。你只需要往里填内容。

---

## 练习流程

### 第 0 步：先读，别急着写

**读 `docs\01-概述与环境.md`。** 它讲了 MCP 是什么、
和 function calling 的区别（最容易搞混的地方）、环境怎么配、
以及**五个必踩的坑**——提前看能省你几小时。

后面四篇（02~05）是每个 Demo 的配套讲解，**边写边看**，不用一次读完。

`docs\06-项目整合与面试.md` 最后再看，那是简历和面试话术。

### 第 1 步：教程四步（理解 MCP）

先跑通跟数据库无关的四个文件，把 MCP 的链路搞明白：

| 顺序 | 填哪个文件 | 文档章节 |
|---|---|---|
| 1 | `step1\CalculatorServer.java` | 全量代码 2.1 |
| 2 | `step2\ManualClient.java` | 全量代码 2.2 |
| 3 | `step3\McpToClaudeTool.java` | 全量代码 2.3 |
| 4 | `step4\AgentMain.java` | 全量代码 2.4 |

**注意第 2 步 —— 那里还没有 LLM。** 是你自己决定调 `add`、自己填参数。
把这一步想明白，MCP 和 function calling 就永远不会再混淆了。

每填完一个就跑一下：

```bash
mvn -q compile exec:exec -DmainClass=com.example.mcp.step2.ManualClient
```

### 第 2 步：数据库 MCP（本项目主体）

**建议从下往上填**，因为下层不依赖上层，每一步都能单独验证：

| 顺序 | 填哪个文件 | 说明 |
|---|---|---|
| 1 | `db\Db.java` | 数据源。**这是唯一需要改的层** |
| 2 | `db\SqlGuard.java` | 安全层。★ 最需要想清楚的一个 |
| 3 | `db\AuditLog.java` | 审计日志 |
| 4 | `db\SchemaIntrospector.java` | 表结构自省 |
| 5 | `db\DbMcpServer.java` | ★ 主入口，4 个工具 |
| 6 | `core\StdioLifecycle.java` | 进程生命周期 |
| 7 | `core\McpAgent.java` | agent loop |
| 8 | `db\DbAgentMain.java` | 接 LLM 的入口 |

---

## 怎么验证

`DbClientDemo` 和 `ReadOnlyAccountDemo` 也清空了，但它们的代码在**文档第 5 节**。
把这两个也敲出来（或者直接从文档复制），它们是你的裁判。

```bash
# 编译
mvn -q clean compile

# ① 教程部分
mvn -q exec:exec -DmainClass=com.example.mcp.step2.ManualClient

# ② 数据库：功能 + 安全（不需要 API Key）
mvn -q exec:exec -DmainClass=com.example.mcp.db.DbClientDemo

# ③ 数据库：只读账号（不需要 API Key）
mvn -q exec:exec -DmainClass=com.example.mcp.db.ReadOnlyAccountDemo

# ④ 自检（不需要 API Key）
mvn -q exec:exec -DmainClass=com.example.mcp.db.DbAgentMain -DappArgs=check

# ⑤ 实际提问（需要 ANTHROPIC_API_KEY）
set ANTHROPIC_API_KEY=sk-ant-...
mvn -q exec:exec -DmainClass=com.example.mcp.db.DbAgentMain
```

### 预期结果

| 检查项 | 期望 |
|---|---|
| `DbClientDemo` ② | **7 个攻击用例全部 ✅** |
| `DbClientDemo` ③ | **6 个误伤检查全部 ✅** |
| `ReadOnlyAccountDemo` | **5 个写操作被拒 + 2 个读操作正常** |
| 跑完后 | 无残留 java 进程 |

### 卡住了怎么定位

| 症状 | 大概率是哪里 |
|---|---|
| 攻击用例没拦住 | `SqlGuard` 的掩码或黑名单 |
| 正常查询被误伤 | `SqlGuard.mask()` 处理引号/注释有误 |
| `LIMIT 999999` 没被收敛到 200 | `SqlGuard.enforceLimit()` |
| 大表查询没显示「已达上限 200 行」 | 同上 |
| 所有查询都报 `Admin rights required` | `Db` 里只读连接的 URL 混进了 `DB_CLOSE_DELAY` |
| 只读账号居然能写 | `Db.createReadOnlyAccount()` 的 `GRANT` |
| 跑完有残留 java 进程 | `StdioLifecycle` 的 EOF 检测 |

---

## 两个必须记住的命令行细节

**① 传参数用 `-DappArgs=`，不要用 `-Dexec.args=`**

exec 插件会把 `exec.args` 插到**主类名之前**，`java` 会把参数当成主类名，
报 `ClassNotFoundException`。这个坑很隐蔽，因为看起来完全合理。

**② Windows 下 `ANTHROPIC_API_KEY` 这样设**

```cmd
set ANTHROPIC_API_KEY=sk-ant-...
```

PowerShell 是 `$env:ANTHROPIC_API_KEY="sk-ant-..."`。

---

## 实在写不出来了

`.reference\java\` 里有一份完整实现。

```bash
# 恢复某个文件（看清路径）
cp .reference/java/com/example/mcp/db/SqlGuard.java src/main/java/com/example/mcp/db/

# 或者全部恢复（慎用，会把你的进度覆盖掉）
bash restore.sh
```

**建议卡住时只看那一个方法**，对着想明白再自己写。
直接抄会错过所有坑——那些坑才是这个练习的价值。
