#!/usr/bin/env bash
# 从 src/ 生成全量代码文档。重复运行会覆盖 docs/全量代码.md。
set -euo pipefail

OUT="docs/全量代码.md"
SRC="src/main/java/com/example/mcp"

# emit <相对路径> <章节号标题> <一句话说明>
emit() {
  local path="$1" title="$2" note="$3"
  printf '\n### %s\n\n' "$title"
  printf '> %s\n>\n> **文件路径**：`%s`\n\n' "$note" "$path"
  printf '```java\n'
  cat "$path"
  printf '```\n'
}

{
cat <<'HEADER'
# MCP Java 全量代码

> 本文档包含项目的**全部源代码**。
>
> `src/` 目录里是**骨架**（方法签名 + 注释 + TODO），需要你照着本文档填写。
> 动手前请先读 `README.md` 了解练习流程。

---

## 怎么看这份文档

每个文件分三部分：

- **一句话说明** —— 这个文件是干什么的
- **文件路径** —— 填完之后应该放在哪
- **完整代码** —— 直接可用的实现

建议的练习顺序是**从下往上**：先写数据源和安全层（不依赖别的），
再写工具层，最后写 agent 层。

---

## 目录

- [一、构建配置](#一构建配置)
- [二、教程四步 —— 理解 MCP](#二教程四步--理解-mcp)
- [三、核心复用层](#三核心复用层)
- [四、只读数据库 MCP](#四只读数据库-mcp)
- [五、验证程序（不用敲，保持原样）](#五验证程序不用敲保持原样)
- [六、完成度自查](#六完成度自查)

---

## 一、构建配置

HEADER

printf '\n### 1.1 `pom.xml`\n\n'
printf '> 依赖、编译配置、exec 插件的参数传递方式。三个编码参数一个都不能少。\n>\n> **文件路径**：`pom.xml`（工程根目录）\n\n'
printf '```xml\n'
cat pom.xml
printf '```\n'

printf '\n### 1.2 `simplelogger.properties`\n\n'
printf '> 日志配置。核心是**必须走 stderr** —— stdout 是 MCP 的协议通道，污染了会解析失败。\n>\n> **文件路径**：`src/main/resources/simplelogger.properties`\n\n'
printf '```properties\n'
cat src/main/resources/simplelogger.properties
printf '```\n'

cat <<'PART2'

---

## 二、教程四步 —— 理解 MCP

> 这一部分是为了**理解 MCP 本身**，跟数据库无关。
> 建议先跑通这四步，再动第四部分的数据库实现。
>
> 详细讲解见 [MCP-Java-从零到跑通.md](./MCP-Java-从零到跑通.md)。

PART2

emit "$SRC/step1/CalculatorServer.java" "2.1 Step1 —— MCP Server" "一个最小的 MCP Server，暴露 add / get_weather 两个工具。注意这里完全没有 LLM。"
emit "$SRC/step2/ManualClient.java" "2.2 Step2 —— MCP Client（无 LLM）" "直接调用工具，不接模型。这一步是理解 MCP 的关键：它证明 MCP 是「程序调工具」的协议。"
emit "$SRC/step3/McpToClaudeTool.java" "2.3 Step3 —— 桥接层" "把 MCP 工具转成 Claude 工具。会发现它短得离谱，因为两边用的是同一份 JSON Schema。"
emit "$SRC/step4/AgentMain.java" "2.4 Step4 —— 完整 agent loop" "把前三步串起来。所谓 agent，本质就是这个 while 循环。"

cat <<'PART3'

---

## 三、核心复用层

> 这部分跟具体业务无关，是通用的基础设施。

PART3

emit "$SRC/core/StdioLifecycle.java" '3.1 `StdioLifecycle` —— 进程生命周期' '解决官方 SDK 的一个坑：stdio server 在 stdin 关闭后不会自己退出，会导致孤儿进程。这里通过包装 System.in 检测 EOF。'
emit "$SRC/core/McpAgent.java" '3.2 `McpAgent` —— 可复用 agent loop' '从 Step4 抽出来的生产级版本。比教学版多了三件事：客户端懒加载、工具结果长度上限、工具崩溃时的兜底。'

cat <<'PART4'

---

## 四、只读数据库 MCP

> 这是本项目的主体。
>
> 详细设计说明见 [只读数据库MCP.md](./只读数据库MCP.md)。
>
> ⚠️ **注意 `SqlGuard` 的定位**：它是第二道墙，用来挡"模型不小心写错"。
> 真正挡住攻击的是 `Db` 里的**只读账号**。两者缺一不可，理由见文档。

PART4

emit "$SRC/db/Db.java" '4.1 `Db` —— 数据源层' '★ 唯一需要改动的层。演示模式自带一套刻意设计过的测试数据（含 5000 行大表，用来验证限流机制真的生效）。'
emit "$SRC/db/SqlGuard.java" '4.2 `SqlGuard` —— SQL 安全层' '★ 核心。掩码 + 分词 + 白名单 + LIMIT 加固。手写状态机而不是正则，是为了正确处理转义引号和注释嵌套。'
emit "$SRC/db/AuditLog.java" '4.3 `AuditLog` —— 审计日志' '记录每次工具调用，包含被拒绝的。被拒绝的比成功的更有信息量。'
emit "$SRC/db/SchemaIntrospector.java" '4.4 `SchemaIntrospector` —— 表结构自省' '把数据库的中文注释读出来给模型看。这是让 AI 查库真正好用的关键。'
emit "$SRC/db/DbMcpServer.java" '4.5 `DbMcpServer` —— MCP Server 主体' '★ 主入口。定义 4 个工具：list_tables / describe_table / run_query / explain_query。工具描述的措辞直接决定模型行为。'
emit "$SRC/db/DbAgentMain.java" '4.6 `DbAgentMain` —— 接 LLM 的入口' '带 check / chat / 单次提问三种模式。'

cat <<'PART5'

---

## 五、验证程序（不用敲，保持原样）

> 这两个文件**不要骨架化也保持原样**。
>
> 原因：你敲完实现后需要一个裁判。如果连验证程序也要自己写，
> 就没法区分"实现写错了"和"测试写错了"。
>
> 直接跑它们来检验你的实现：

PART5

emit "$SRC/db/DbClientDemo.java" '5.1 `DbClientDemo` —— 功能 + 安全验证' '验证工具功能、7 个攻击用例、6 个误伤检查。不需要 API Key。'
emit "$SRC/db/ReadOnlyAccountDemo.java" '5.2 `ReadOnlyAccountDemo` —— 只读账号验证' '绕过全部 Java 代码，直接用只读账号攻击数据库。这是唯一能证明第一道墙有效的方法。'

cat <<'PART6'

---

## 六、完成度自查

填完之后，按这个顺序验证：

```bash
# 1. 编译
mvn -q clean compile

# 2. 教程部分（先确认 MCP 链路是通的）
mvn -q exec:exec -DmainClass=com.example.mcp.step2.ManualClient

# 3. 数据库：功能 + 安全
mvn -q exec:exec -DmainClass=com.example.mcp.db.DbClientDemo

# 4. 数据库：只读账号
mvn -q exec:exec -DmainClass=com.example.mcp.db.ReadOnlyAccountDemo

# 5. 自检（不需要 API Key）
mvn -q exec:exec -DmainClass=com.example.mcp.db.DbAgentMain -DappArgs=check
```

### 预期结果

| 检查项 | 期望 |
|---|---|
| 编译 | 无错 |
| Step2 输出 | 列出 add / get_weather 两个工具，`add(3, 4) -> 3 + 4 = 7` |
| `DbClientDemo` ② | **7 个攻击用例全部 ✅** |
| `DbClientDemo` ③ | **6 个误伤检查全部 ✅** |
| `ReadOnlyAccountDemo` | **5 个写操作全部被拒 + 2 个读操作正常** |
| 残留进程 | 0（跑完后 `tasklist \| grep java` 应该没有多余进程） |

### 如果不对

| 症状 | 大概率是哪里的问题 |
|---|---|
| 攻击用例没拦住 | `SqlGuard` 的掩码或黑名单 |
| 正常查询被误伤 | `SqlGuard.mask()` 处理引号/注释有误 |
| `LIMIT 999999` 没被收敛到 200 | `SqlGuard.enforceLimit()` |
| 大表查询没显示"已达上限 200 行" | 同上 |
| 所有查询都报 `Admin rights required` | `Db` 里只读连接的 URL 混进了 `DB_CLOSE_DELAY` |
| 只读账号能写 | `Db.createReadOnlyAccount()` 的 `GRANT` 没生效 |
| 跑完有残留 java 进程 | `StdioLifecycle` 的 EOF 检测没接上 |

---

## 附：完整实现参考

工作目录下的 `.reference/java/` 里保留了一份**完整实现**，是生成本文档时的原始代码。

**强烈建议先自己敲一遍再看**——直接抄会错过所有坑。
实在卡住了再对照。
PART6
} > "$OUT"

echo "已生成 $OUT"
wc -l "$OUT"
