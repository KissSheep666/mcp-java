# MCP + Java：从零到跑通

> 本文档里的每一行代码都在本机实际编译运行过。
> 环境：JDK 21.0.12 / Maven 3.9.16 / MCP Java SDK 2.0.0 / anthropic-java 2.34.0 / Windows 11

---

## 目录

1. [MCP 到底解决什么问题](#1-mcp-到底解决什么问题)
2. [三个角色与两种传输](#2-三个角色与两种传输)
3. [协议层：其实是 JSON-RPC 2.0](#3-协议层其实是-json-rpc-20)
4. [环境准备](#4-环境准备)
5. [Step 1 — 写一个 MCP Server](#5-step-1--写一个-mcp-server)
6. [Step 2 — 写一个 MCP Client（关键：这里还没有 LLM）](#6-step-2--写一个-mcp-client关键这里还没有-llm)
7. [Step 3 — 桥接：MCP 工具 ≠ Claude 工具](#7-step-3--桥接mcp-工具--claude-工具)
8. [Step 4 — 闭合 agent loop](#8-step-4--闭合-agent-loop)
9. [解剖一次完整交互](#9-解剖一次完整交互)
10. [踩坑清单](#10-踩坑清单)
11. [进阶方向](#11-进阶方向)
12. [附录：API 速查](#12-附录api-速查)

---

## 1. MCP 到底解决什么问题

先说结论：**MCP 解决的是"工具接入"的标准化问题，不是"模型会不会用工具"的问题。**

假设你有 3 个模型（Claude、GPT、本地模型）和 5 个工具（数据库、文件系统、内部 API、搜索、代码执行）。没有标准时，每接一个组合都要写一遍胶水：

```
        工具1   工具2   工具3   工具4   工具5
Claude   ✍️     ✍️      ✍️      ✍️      ✍️
GPT      ✍️     ✍️      ✍️      ✍️      ✍️
本地模型  ✍️     ✍️      ✍️      ✍️      ✍️
```

15 份胶水代码。加一个新工具要改 3 个地方。

MCP 把这个矩阵拆成两半：**工具方实现一次 MCP Server，模型方实现一次 MCP Client**，中间用统一协议对话。

```
        工具1   工具2   工具3   工具4   工具5
Client   ────────── MCP 协议 ──────────
```

3 + 5 = 8 份，而且新增工具只影响工具侧。

### 一个最常见的误解

> "MCP 是不是就是 function calling？"

**不是。这两件事是正交的。**

| | 管什么 | 举例 |
|---|---|---|
| **Function calling** | 模型**怎么表达**"我想调用某个工具" | Claude 返回 `stop_reason: "tool_use"` + 一个 `tool_use` 块 |
| **MCP** | 工具**怎么被发现、怎么被调用** | `tools/list` 列出有哪些工具，`tools/call` 执行 |

一个 MCP Server 完全可以被一个**完全没有 LLM** 的程序使用——这正是本文 Step 2 要演示的。反过来，function calling 也可以调用完全不走 MCP 的工具（就是你手写 JSON Schema 那种）。

只有当你想让**模型**去调**别人写好的 MCP 工具**时，才需要把两者接起来。那层胶水就是 Step 3，你会看到它薄得惊人。

### 类比

如果你熟悉 LSP（Language Server Protocol）：MCP 对 AI 工具的意义，就相当于 LSP 对编辑器的意义。VS Code 不需要为每个语言写一套补全逻辑，只要语言方实现一个 LSP Server。MCP 是同一套思路。

---

## 2. 三个角色与两种传输

MCP 规范里有三个角色，**别搞混 Host 和 Client**：

| 角色 | 是什么 | 在本教程里 |
|---|---|---|
| **Host** | 用户面对的应用，负责编排和 UI | 你的程序（比如一个 CLI、一个 IDE 插件） |
| **Client** | Host 内部与**单个** Server 的连接器 | `McpSyncClient` |
| **Server** | 提供工具/资源/提示词的一方 | `CalculatorServer` |

一个 Host 通常持有**多个** Client，每个 Client 连一个 Server。Client 和 Server 是 1:1 的。

```
┌─────────────── Host（你的程序）───────────────┐
│                                                │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐   │
│  │ Client A │   │ Client B │   │ Client C │   │
│  └────┬─────┘   └────┬─────┘   └────┬─────┘   │
└───────┼──────────────┼──────────────┼─────────┘
        │              │              │
   ┌────▼────┐    ┌────▼────┐    ┌────▼────┐
   │Server 1 │    │Server 2 │    │Server 3 │
   │(文件系统)│    │(数据库)  │    │(自研)   │
   └─────────┘    └─────────┘    └─────────┘
```

### 传输方式

| 传输 | 场景 | 说明 |
|---|---|---|
| **STDIO** | 本地子进程 | 通过 stdin/stdout 收发 JSON-RPC。客户端负责 fork server 进程。**本教程用这个** |
| **Streamable HTTP** | 远程服务 | 一个 HTTP endpoint 同时处理请求和流式响应。**新项目首选** |
| ~~HTTP + SSE~~ | — | **已废弃**，别在新项目里用 |

选哪个的判断很简单：server 和 host 在同一台机器上 → STDIO；server 是独立部署的服务 → Streamable HTTP。

---

## 3. 协议层：其实是 JSON-RPC 2.0

MCP 没有重新发明 RPC，它就是 **JSON-RPC 2.0** 加上一套约定好的方法名。

一次 `tools/call` 在线上长这样：

```jsonc
// 客户端 → 服务端
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "add",
    "arguments": { "a": 3, "b": 4 }
  }
}

// 服务端 → 客户端
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      { "type": "text", "text": "3 + 4 = 7" }
    ]
  }
}
```

就这么朴素。理解了这一点，后面 SDK 的所有 API 都不再有魔法。

### 生命周期

```
Client                                          Server
  │                                                │
  │──── initialize (协议版本 + 我的能力) ─────────>│
  │<─── InitializeResult (你的身份 + 你的能力) ────│
  │                                                │
  │──── notifications/initialized ────────────────>│   ← 握手完成的分界线
  │                                                │
  │──── tools/list ───────────────────────────────>│
  │<─── 工具清单 ──────────────────────────────────│
  │                                                │
  │──── tools/call ───────────────────────────────>│
  │<─── 调用结果 ──────────────────────────────────│
  │                                                │
  │──── shutdown / 关闭连接 ──────────────────────>│
```

**能力协商（capability negotiation）** 是这个握手的重点。双方各自声明自己支持什么：

- Client 可能支持：`roots`（告诉 server 有哪些工作目录）、`sampling`（让 server 反过来请求 Client 调 LLM）、`elicitation`（让 server 向用户追问信息）
- Server 可能支持：`tools`、`resources`、`prompts`、`logging`

**只有双方都声明支持的能力才能用。** 这就是为什么 Step 1 里我们要写 `.capabilities(ServerCapabilities.builder().tools(true).build())`——不声明的话，客户端不会去调 `tools/list`。

> 想亲眼看这个握手过程？把 `src/main/resources/simplelogger.properties` 里的日志级别改成 `info` 再跑一次 Step 2，你能看到完整的协议版本协商和能力交换。

---

## 4. 环境准备

### 依赖

完整内容见 `pom.xml`，核心就三块：

```xml
<properties>
  <maven.compiler.release>17</maven.compiler.release>
  <mcp.sdk.version>2.0.0</mcp.sdk.version>
  <anthropic.sdk.version>2.34.0</anthropic.sdk.version>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.modelcontextprotocol.sdk</groupId>
      <artifactId>mcp-bom</artifactId>
      <version>${mcp.sdk.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp</artifactId>
  </dependency>
  <dependency>
    <groupId>com.anthropic</groupId>
    <artifactId>anthropic-java</artifactId>
    <version>${anthropic.sdk.version}</version>
  </dependency>
  <dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.16</version>
  </dependency>
</dependencies>
```

关于那个 `mcp` 构件：它是个**便捷包**，等于 `mcp-core` + Jackson3 JSON 实现 + 传输实现。想更精细地控制依赖，可以只引 `mcp-core` 并自己挑 `mcp-json-jackson2` 或 `mcp-json-jackson3`。

### 三个必须注意的构建配置

这三个如果不配，后面一定会出问题：

**① 用 `exec:exec` 而不是 `exec:java`**

```xml
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <version>3.5.0</version>
  <configuration>
    <executable>java</executable>
    <arguments>
      <argument>-Dfile.encoding=UTF-8</argument>
      <argument>-Dstdout.encoding=UTF-8</argument>
      <argument>-Dstderr.encoding=UTF-8</argument>
      <argument>-classpath</argument>
      <classpath/>
      <argument>${mainClass}</argument>
      <argument>${appArgs}</argument>
    </arguments>
  </configuration>
</plugin>
```

`${appArgs}` 是自定义属性（在 `<properties>` 里定义为空值），用来给被测程序传参：

```bash
mvn -q exec:exec -DmainClass=xxx -DappArgs="你要问的问题"
```

**别用标准的 `-Dexec.args`**——exec 插件会把它插到**主类名之前**，
结果 `java` 把参数当成了主类名，报 `ClassNotFoundException`。
这个坑很隐蔽，因为看起来完全合理。

`exec:java` 在**同一个 JVM** 里跑，`System.getProperty("java.class.path")` 拿到的是 Maven 自己的 classpath，不是真的。而 Step 2 和 Step 4 需要拿这个 classpath 去 fork server 子进程。`exec:exec` 是真的起一个新进程，classpath 是真的。

**② 编码三个参数一个都不能少**

这是个 Windows 中文环境特有的深坑：

- Java 18+ 已经把 `file.encoding` 默认成 UTF-8 了
- 但 `System.out` 用的是 `stdout.encoding`，它在 Windows 上**仍然跟随控制台代码页**（中文系统是 GBK）

结果就是：文件读写完全正常，`System.out.println` 出来的中文全是乱码。三个参数缺任何一个都会出问题。

**③ 日志必须走 stderr**

STDIO 传输把 **stdout 独占为 JSON-RPC 通道**。任何打到 stdout 的东西——`System.out.println`、日志框架的默认输出、某个依赖库的调试信息——都会污染协议流。

`slf4j-simple` 默认输出到 `System.err`，正好安全。`src/main/resources/simplelogger.properties` 里也显式写了 `logFile=System.err` 作为双保险。

---

## 5. Step 1 — 写一个 MCP Server

完整代码：`src/main/java/com/example/mcp/step1/CalculatorServer.java`

```java
public final class CalculatorServer {

    public static void main(String[] args) {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();

        // 1) 用 JSON Schema 描述入参
        Map<String, Object> addSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "a", Map.of("type", "integer", "description", "第一个加数"),
                        "b", Map.of("type", "integer", "description", "第二个加数")
                ),
                "required", List.of("a", "b")
        );

        // 2) 声明工具
        McpSchema.Tool addTool = McpSchema.Tool.builder("add", addSchema)
                .description("计算两个整数之和。当用户询问加法运算时使用。")
                .build();

        // 3) 组装并启动
        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(jsonMapper))
                .serverInfo("calc-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .toolCall(addTool, (exchange, request) -> {
                    int a = ((Number) request.arguments().get("a")).intValue();
                    int b = ((Number) request.arguments().get("b")).intValue();
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(a + " + " + b + " = " + (a + b))
                            .build();
                })
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 逐点解析

**关于 JSON Schema**

```
"a", Map.of("type", "integer", "description", "第一个加数")
```

这就是一份**普普通通的 JSON Schema**。MCP 没有发明新的 schema 语言，直接复用。

这一点值得停一下想一想：正因为如此，Step 3 里把 MCP 工具转成 Claude 工具才会那么短——两边用的是同一个东西。

**关于 `description`**

```java
.description("计算两个整数之和。当用户询问加法运算时使用。")
```

**这是整个工具定义里最重要的字段**，比参数 schema 重要得多。模型完全靠它决定"什么时候该调这个工具"。

写法建议是「**做什么** + **什么时候用**」：

- ✅ `"查询指定城市的当前天气。当用户询问天气时使用。"`
- ❌ `"天气工具"`

模型看不到你的代码，它只有这句话。

**关于 `toolCall` 的 lambda**

```java
.toolCall(addTool, (exchange, request) -> { ... })
```

两个参数：

- `exchange`（`McpSyncServerExchange`）—— 用来**反向**跟客户端通信。比如调用 `exchange.createMessage(...)` 请求客户端帮忙调一次 LLM（这就是 sampling），或者向用户追问信息（elicitation）。本教程用不到，但它是 MCP 比"普通函数注册"强的地方。
- `request`（`CallToolRequest`）—— 含 `name()` 和 `arguments()`。`arguments()` 就是调用方（后面是模型）填的参数。

**关于 `capabilities`**

```java
.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
```

只声明 `tools`。如果后面要加 resources 或 prompts，就在这里补：

```java
.capabilities(McpSchema.ServerCapabilities.builder()
        .tools(true)
        .resources(false, true)   // subscribe=false, listChanged=true
        .prompts(true)
        .logging()
        .build())
```

**关于最后的 `CountDownLatch`**

stdio 传输在后台线程读 stdin。主线程如果直接返回，进程可能立刻就退出了。挂住主线程是最稳妥的做法。

### 怎么验证它

你没法直接 `java CalculatorServer` 手动测——它等的是 stdin 上的 JSON-RPC，不是命令行参数。三种验证方式：

1. **用 Step 2 的客户端**（本教程的方式）
2. **用 MCP Inspector**（官方调试工具，可视化界面，最推荐日常调试）
3. **手动喂 JSON**：`echo '{"jsonrpc":"2.0","id":1,...}' | java ...`，能跑但很痛苦

---

## 6. Step 2 — 写一个 MCP Client（关键：这里还没有 LLM）

完整代码：`src/main/java/com/example/mcp/step2/ManualClient.java`

**这一步是整个教程最重要的部分**，因为它把"MCP"和"LLM"彻底分开了。跑通这一步，你对 MCP 的理解就完成了 80%。

```java
// 1) 描述"怎么启动 server"
ServerParameters serverParams = ServerParameters.builder("java")
        .args("-Dfile.encoding=UTF-8",
              "-Dstdout.encoding=UTF-8",
              "-Dstderr.encoding=UTF-8",
              "-classpath", System.getProperty("java.class.path"),
              "com.example.mcp.step1.CalculatorServer")
        .build();

// 2) 建立连接
McpSyncClient client = McpClient.sync(new StdioClientTransport(serverParams, jsonMapper))
        .requestTimeout(Duration.ofSeconds(30))
        .build();

client.initialize();                          // 3) 握手

McpSchema.ListToolsResult tools = client.listTools();   // 4) 发现工具
McpSchema.CallToolResult r = client.callTool(           // 5) 调用工具
        new McpSchema.CallToolRequest("add", Map.of("a", 3, "b", 4)));

client.closeGracefully();                     // 6) 关闭
```

### 实际输出

```
已连接: calc-server v1.0.0
server 能力: ServerCapabilities[..., tools=ToolCapabilities[listChanged=true]]

=== tools/list ===
• add
  描述  : 计算两个整数之和。当用户询问加法运算时使用。
  schema: {required=[a, b], type=object, properties={b={type=integer, description=第二个加数}, a={type=integer, description=第一个加数}}}
• get_weather
  描述  : 查询指定城市的当前天气。当用户询问天气时使用。
  schema: {required=[city], type=object, properties={city={type=string, description=城市名，例如：北京}}}

=== tools/call ===
add(3, 4)          -> 3 + 4 = 7
get_weather(北京)  -> 北京：多云，11°C

=== 故意传错参数（a 传字符串）===
isError = true
content = Tool (add) input validation failed: Validation failed: JSON schema validation errors: [/a: 已找到 string，必须是 integer]
```

### 逐点解析

**`ServerParameters` —— MCP 设计的精髓所在**

```java
ServerParameters.builder("java")
    .args(..., "com.example.mcp.step1.CalculatorServer")
```

客户端**完全不关心 server 是什么语言写的**。它只知道「用这个命令能把它拉起来，然后用 stdio 说 JSON-RPC」。

换成 Python 写的 server 就是：

```java
ServerParameters.builder("python").args("-m", "my_server").build()
```

换成 Node：

```java
ServerParameters.builder("node").args("server.js").build()
```

这就是为什么 `ServerParameters` 的设计是「命令 + 参数」而不是「类名」——语言无关是这个协议的核心价值。

**`initialize()` 做了什么**

它交换双方身份和能力。之后你可以问客户端拿这些信息：

```java
client.getServerInfo()          // Implementation[name=calc-server, version=1.0.0]
client.getServerCapabilities()  // 对方支持什么
client.getInstructions()        // server 给的系统提示词建议
```

**`listTools()` 返回了什么**

就是 Step 1 里声明的那份 `name` / `description` / `inputSchema`，原样传过来。

在没有 LLM 的世界里，它的用途是让调用方（人或程序）知道能干什么。

在第 4 步你会看到，**它恰好就是喂给 Claude 的工具清单**——同一个返回值，两个用途。

**`callTool()` 的参数是个普通 Map**

```java
new McpSchema.CallToolRequest("add", Map.of("a", 3, "b", 4))
```

没有魔法，没有模型。就是我们自己决定要调 `add`，自己填了 `a=3, b=4`。

**服务端参数校验（很多人不知道）**

最后那段"故意传错参数"值得注意：SDK **默认**会按 `inputSchema` 校验入参，不合法直接返回 `isError=true`，**根本不进 handler**。

```
content = Tool (add) input validation failed: ... [/a: 已找到 string，必须是 integer]
```

这是一道免费的安全网。在第 4 步里它的意义会放大——因为那时候参数是**模型生成的**，可能是任何东西。

想关掉（不推荐）：server 端 `.validateToolInputs(false)`。

**`closeGracefully()` 不能省**

它会终止 fork 出来的 server 子进程。漏掉这行的典型症状是：跑完一次后，一堆 `java` 进程赖在任务管理器里不走。

---

## 7. Step 3 — 桥接：MCP 工具 ≠ Claude 工具

完整代码：`src/main/java/com/example/mcp/step3/McpToClaudeTool.java`

这是整个教程最短的一个类，短到可能会让你怀疑"就这？"

```java
public static Tool toClaudeTool(McpSchema.Tool mcpTool) {
    Map<String, Object> schema = mcpTool.inputSchema();

    // properties
    Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
    Object rawProps = schema.get("properties");
    if (rawProps instanceof Map<?, ?> props) {
        props.forEach((key, value) ->
                propsBuilder.putAdditionalProperty(String.valueOf(key), JsonValue.from(value)));
    }

    Tool.InputSchema.Builder schemaBuilder = Tool.InputSchema.builder()
            .properties(propsBuilder.build());

    // required
    Object rawRequired = schema.get("required");
    if (rawRequired instanceof List<?> required && !required.isEmpty()) {
        schemaBuilder.required(required.stream().map(String::valueOf).toList());
    }

    return Tool.builder()
            .name(mcpTool.name())
            .description(mcpTool.description() == null ? "" : mcpTool.description())
            .inputSchema(schemaBuilder.build())
            .build();
}
```

### 为什么这么短

因为**两边用的是同一个东西：JSON Schema**。

```
MCP 的 Tool.inputSchema     →  Map<String, Object>   （一份 JSON Schema）
Claude 的 Tool.inputSchema  →  结构化的 Properties
```

本质上只是把同一份 schema 从左口袋挪到右口袋，一个字段一个字段地搬。

### 这个类回答了一个常见困惑

> "我到底该用 MCP 还是直接写 function calling？"

看这个类就明白了——**它们不是竞品，是两层**：

- **Function calling** 是模型侧的机制：模型输出一个 `tool_use` 块，表示"我想调这个"。
- **MCP** 是工具侧的机制：工具怎么被发现、怎么被调用。

你手写 30 行 JSON Schema 是 function calling；你从 MCP server 拉一份 schema 转成 Claude 的格式，也是 function calling。区别只在 schema 从哪来、调用发给谁。

**MCP 的价值不是替代 function calling，而是让工具的来源标准化了。** 你的 agent 可以今天接一个别人写的 GitHub MCP server，明天接一个数据库的，代码一行不用改——因为它们都长一个样。

### 一个实际工程里的注意点

真实的 MCP 工具 schema 往往比这里复杂得多，可能包含：

- `enum`、`anyOf`、嵌套对象、数组
- `$ref` / `$defs` 引用
- `default` 值
- 各种 JSON Schema 方言的字段

上面这段代码用的是"原样搬运"策略——把不合法的字段也一起带过去。实践中你可能需要：

1. **依赖 Claude 的宽容度**：它的 `input_schema` 接受标准 JSON Schema 的大部分子集
2. **过滤**：遇到不支持的字段时剔除，而不是整个工具报错
3. **用 strict 模式**：Claude 支持 `.strict(true)` 保证参数严格符合 schema，但要求 schema 里必须有 `additionalProperties: false`

第 3 点在生产环境很值钱——它能把"模型编了个不存在的参数"这类问题挡在调用之前。

---

## 8. Step 4 — 闭合 agent loop

完整代码：`src/main/java/com/example/mcp/step4/AgentMain.java`

现在把前三步串起来。所谓 "agent"，本质就是下面这个 `while` 循环：

```
   用户提问
      │
      ▼
   ① 把 MCP 工具清单转成 Claude 的 tools，连同问题一起发给 Claude
      │
      ▼
   ② Claude 返回 stop_reason = "tool_use"，带着它想调的工具和参数
      │
      ▼
   ③ 我们拿着这个参数，通过 MCP client 调 MCP server
      │
      ▼
   ④ 把结果包成 tool_result 塞回对话，再发给 Claude
      │
      ▼
   ⑤ Claude 看到结果，返回 stop_reason = "end_turn" 和最终回答
      │
      ▼
    循环结束
```

核心代码：

```java
// ① 工具转换：MCP 发现 → Claude 格式
List<Tool> claudeTools = mcp.listTools().tools().stream()
        .map(McpToClaudeTool::toClaudeTool)
        .toList();

List<MessageParam> messages = new ArrayList<>();
messages.add(MessageParam.builder()
        .role(MessageParam.Role.USER).content(question).build());

// ② loop
for (int turn = 1; turn <= MAX_TURNS; turn++) {

    MessageCreateParams.Builder params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .messages(messages);
    claudeTools.forEach(params::addTool);

    Message response = claude.messages().create(params.build());

    List<ContentBlockParam> assistantBlocks = new ArrayList<>();
    List<ContentBlockParam> toolResults = new ArrayList<>();

    for (ContentBlock block : response.content()) {
        assistantBlocks.add(block.toParam());

        if (!block.isToolUse()) {
            block.text().ifPresent(t -> System.out.println("Claude: " + t.text()));
            continue;
        }

        // ③ 模型要调工具了 —— MCP 出场
        ToolUseBlock toolUse = block.toolUse().orElseThrow();
        Map<String, Object> mcpArgs = toolUse._input().convert(Map.class);

        McpSchema.CallToolResult mcpResult = mcp.callTool(
                new McpSchema.CallToolRequest(toolUse.name(), mcpArgs));

        toolResults.add(ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder()
                        .toolUseId(toolUse.id())
                        .content(extractText(mcpResult))
                        .isError(Boolean.TRUE.equals(mcpResult.isError()))
                        .build()));
    }

    messages.add(MessageParam.builder()
            .role(MessageParam.Role.ASSISTANT)
            .contentOfBlockParams(assistantBlocks).build());

    // ⑤ 没有工具要调 = 任务完成
    if (toolResults.isEmpty()) return;

    // ④ 回传结果
    messages.add(MessageParam.builder()
            .role(MessageParam.Role.USER)
            .contentOfBlockParams(toolResults).build());
}
```

### 逐点解析

**`block.toParam()` —— 别手搓**

```java
assistantBlocks.add(block.toParam());
```

SDK 提供的转换，把响应块转成可回传的参数块。

**为什么不能手动重建？** 因为 thinking 块带一个 `signature` 字段，是模型生成时的密码学签名，**必须逐字节原样保留**。手动重建很容易破坏它，然后请求会报错。

Java SDK 有 `toParam()`，直接用。其他语言如果没有这个 helper，就得逐个 variant 小心处理。

**`stop_reason` 的处理**

模型可能因为安全策略拒绝请求。**拒绝时 `content` 可能是空的**，所以读 content 前必须先判断：

```java
if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
    System.out.println("模型拒绝了本次请求。");
    response.stopDetails().ifPresent(d -> System.out.println("  类别: " + d.category()));
    return;
}
```

这是生产代码里最容易漏、且一漏就 NPE 的地方。

**退出条件用 `toolResults.isEmpty()` 而不是只看 stopReason**

```java
if (toolResults.isEmpty()) return;
```

因为有些情况（模型既说话又想调工具）`stopReason` 会有歧义。判断"这一轮到底有没有工具要执行"更稳。

**所有 `tool_result` 必须装在【同一条】 user 消息里**

```java
messages.add(MessageParam.builder()
        .role(MessageParam.Role.USER)
        .contentOfBlockParams(toolResults)   // ← 一个 list，一条消息
        .build());
```

模型可以在一轮里并行请求多个工具。这些结果必须合并成**一条** user 消息回传。拆成多条会**悄悄训练模型不再并行调用工具**——你甚至不会看到报错，只是性能慢慢变差。

**`is_error` 必须传**

```java
.isError(Boolean.TRUE.equals(mcpResult.isError()))
```

工具报错时要显式标记。否则模型会把错误信息当成**正常返回值**，然后基于它继续编下去。

**`maxTokens` 要给足**

```java
.maxTokens(16000L)
```

Claude Opus 5 **默认开启思考**，而 `maxTokens` 是「思考 + 正文」的总上限。给得太紧，回答会在半路被截断。

想降成本/延迟，调 `effort` 而不是砍 `maxTokens`：

```java
.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
```

**防御性上限**

```java
private static final int MAX_TURNS = 10;
```

万一模型陷入循环，别让它无限烧 token。

### 运行

```bash
export ANTHROPIC_API_KEY=sk-ant-...

mvn -q compile exec:exec -DmainClass=com.example.mcp.step4.AgentMain

# 带自定义问题
mvn -q compile exec:exec -DmainClass=com.example.mcp.step4.AgentMain \
    -DappArgs="北京天气怎么样？另外帮我算 999 加 111"
```

---

## 9. 解剖一次完整交互

以「北京天气怎么样？另外帮我算一下 1234 加 5678」为例，屏幕上会看到：

```
用户: 北京天气怎么样？另外帮我算一下 1234 加 5678
────────────────────────────────────────────────────────────
已加载 2 个 MCP 工具：[add, get_weather]
────────────────────────────────────────────────────────────
  [调用] get_weather {city=北京}
  [结果] 北京：多云，11°C
  [调用] add {a=1234, b=5678}
  [结果] 1234 + 5678 = 6912
Claude: 北京现在多云，11°C。
        另外 1234 + 5678 = 6912。
────────────────────────────────────────────────────────────
(共 2 轮)
```

**轮 1**：发问题 + 2 个工具定义 → Claude 返回 `stop_reason=tool_use`，**一轮里并行请求了两个工具**（注意它们在同一次响应里）→ 我们分别调 MCP → 两个结果合并成一条 user 消息回传。

**轮 2**：Claude 看到两个结果 → 没有更多工具要调 → `stop_reason=end_turn` → 输出最终回答 → `toolResults` 为空 → 退出。

### 值得注意的三件事

**① 模型选择了并行调用**

它本可以分两轮（先调天气，拿到结果，再调 add）。它选择了一轮解决。这不是必然的——取决于模型判断。

这也是为什么上一条规则（所有 tool_result 装一条消息）很重要。

**② 模型不知道 MCP 的存在**

Claude 只看到两个普通的工具定义。整个 MCP 握手、子进程管理、JSON-RPC 往返，对模型是完全透明的。

**③ 试试它没工具时会怎样**

```bash
-Dexec.args="帮我算 999 乘 111"
```

没有任何乘法工具。观察模型会怎么做——它可能会说"我没有乘法工具"，也可能用加法硬凑（999×111 = 999×100 + 999×11 …）。这是个很好的练习，能让你直观感受到**工具描述对模型行为的引导作用**。

---

## 10. 踩坑清单

按踩到概率排序，前三个几乎人人都会中招。

### ① stdout 被污染（头号杀手）

**症状**：客户端报 JSON 解析错误；或者干脆卡住不动；或者 `initialize` 就失败。

**原因**：STDIO 传输把 stdout 当成独占的 JSON-RPC 通道。任何打到 stdout 的东西都会污染协议流。

常见的污染源：

- `System.out.println("debug")` —— 最常见
- 日志框架默认输出到 stdout（Logback 默认是 stdout，SLF4J simple 默认是 stderr）
- 某个依赖库自己 print 东西

**解法**：

- 日志统一走 stderr
- 调试信息用 `System.err`
- 用 `simplelogger.properties` 显式锁定 `logFile=System.err`

### ② Windows 中文乱码

**症状**：文件读写正常，但 `System.out.println` 出来的中文全是 `?????` 或乱码。

**原因**：Java 18+ 的 `file.encoding` 默认 UTF-8，但 `System.out` 走 `stdout.encoding`，它在 Windows 上跟随控制台代码页（中文系统是 GBK/936）。

**解法**：三个参数缺一不可。

```
-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8
```

注意：**fork 子进程时也要带上**，否则子进程的日志照样乱码。

### ③ 孤儿进程 —— 客户端和服务端两头都要管

**症状**：程序跑完，任务管理器里一堆 `java` 进程不走。跑几十次就卡死机器。

这个坑要分两头说，**只处理一头是不够的**：

**客户端侧** —— 用 `try/finally` 保证关闭：

```java
try {
    client.initialize();
    // ...
} finally {
    client.closeGracefully();
}
```

**服务端侧（更容易漏）** —— 官方 SDK 的 stdio server 有个默认行为：
**主线程一旦挂住就永远不退出，即使 stdin 已经关闭。**

官方示例通常写成 `new CountDownLatch(1).await()`（永久阻塞），
后果是：客户端崩了、被 kill 了、忘了 closeGracefully()，
server 就会一直赖着。

正确做法是自己感知 stdin EOF：

```java
// StdioLifecycle.create() 把 System.in 包一层，
// 读到 EOF（-1）时释放 latch —— stdin 关闭 == 客户端已断开
StdioLifecycle.Handle lifecycle = StdioLifecycle.create(jsonMapper);
McpSyncServer server = McpServer.sync(lifecycle.provider())...build();

lifecycle.awaitClientDisconnect();   // 阻塞到客户端断开
server.closeGracefully();            // 干净退出，退出码 0
```

完整实现在 `core/StdioLifecycle.java`。

**顺带解决另一个噪音**：不做这个的话，客户端只能强杀 server 进程，
而 Windows 上强杀的退出码固定是 1，于是每次正常关闭都会打印

```
WARN StdioClientTransport - MCP server process failed with code 1
```

明明是一次正常关闭，看起来却像崩溃了。

### ④ 工具描述写得太随意

**症状**：模型该调工具时不调，不该调时乱调。

**原因**：`description` 是模型判断"要不要用这个工具"的**唯一依据**。写"天气工具"这种描述，模型基本靠猜。

**解法**：写清楚「做什么」+「什么时候用」。必要时加上反例。

```java
.description("查询指定城市的当前天气。当用户询问天气、气温、是否下雨时使用。" +
             "注意：只支持中国城市，不支持查询历史天气。")
```

### ⑤ `maxTokens` 给太小

**症状**：回答在句子中间被截断，`stop_reason` 是 `max_tokens`。

**原因**：Opus 5 默认开启思考，`maxTokens` 是思考 + 正文的总上限。

**解法**：给足余量（16000 起步）。要控制成本调 `effort`，不要砍 `maxTokens`。

### ⑥ 忘记处理 `refusal`

**症状**：偶尔 NPE，栈顶是读 `response.content()` 的地方。

**原因**：模型拒绝请求时返回 `stop_reason: "refusal"`，且 **`content` 可能是空列表**。

**解法**：读 content 前先判断 `stopReason`。

### ⑦ 工具结果拆成多条消息

**症状**：没有报错，但模型渐渐不再并行调用工具，延迟越来越高。

**原因**：所有 `tool_result` 必须装在**同一条** user 消息里。拆开会让模型学到"一次只调一个"。

**解法**：`contentOfBlockParams(toolResults)` 传一整个 list。

### ⑧ 混淆 Host / Client / Server

**症状**：架构设计阶段走弯路，比如以为一个 Client 能连多个 Server。

**解法**：Client 和 Server 是 **1:1**。一个 Host 持有多个 Client，每个 Client 连一个 Server。

---

## 11. 进阶方向

> **📄 想看一个完整的真实实现？**
> 本工程里已经有一个：**[只读数据库查询 MCP](./只读数据库MCP.md)**。
> 它把本文档的所有概念用在一个真实场景上，并补上了三层安全设计
> （只读账号 / SQL 白名单 / 超时与行数上限）、审计日志、专用测试库。
> 建议读完本文档后直接看它。

跑通最小链路后，按这个顺序往下走最自然：

### 1. Resources —— 让 server 提供"数据"而不只是"动作"

Tools 是「模型主动调用的动作」，Resources 是「可以被读取的数据」（文件内容、数据库记录、日志）。

```java
.capabilities(McpSchema.ServerCapabilities.builder().resources(false, true).build())
```

客户端侧用 `client.listResources()` / `client.readResource(...)`。

区别在于：Tools 由模型决定调用，Resources 通常由 Host 决定要不要塞进上下文。

### 2. Prompts —— 预置的提示词模板

Server 可以暴露一些"常用提示词"，客户端把它们变成斜杠命令之类的入口。

适合把「这个 server 的正确用法」固化下来，而不是让用户每次手打。

### 3. Sampling —— 让 server 反过来请求 LLM

这个能力最容易被忽略，但很有意思：**Server 可以反向请求 Client 帮忙调一次 LLM**。

意味着一个 MCP server 可以做到"我不内置任何模型，但需要推理时向 Host 借用"。

```java
.toolCall(someTool, (exchange, request) -> {
    var result = exchange.createMessage(/* CreateMessageRequest */);
    // ...
})
```

Client 侧要声明支持：

```java
McpClient.sync(transport)
    .sampling(req -> /* 在这里调你的 LLM */)
    .build()
```

### 4. Streamable HTTP —— 把 server 部署成服务

Server 侧：

```java
McpServer.sync(streamableHttpProvider)
```

Client 侧：

```java
HttpClientStreamableHttpTransport.builder("https://your-server/mcp")
    .httpRequestCustomizer((builder, method, uri, body, ctx) ->
        builder.header("Authorization", "Bearer " + token))
    .build()
```

注意：
- 旧的 HTTP+SSE 传输**已废弃**，别用
- 别忘了鉴权——暴露在公网的 MCP server 就是一个远程代码执行面

### 5. 生产环境的额外考量

- **超时与重试**：`.requestTimeout(...)` 要给合理值，网络抖动是常态
- **工具数量**：工具太多会稀释模型的注意力。考虑按场景拆分多个 server，或者用工具搜索
- **鉴权**：HTTP 传输必须做。STDIO 天然安全（本地进程），但也意味着 server 能拿到你的完整环境
- **审计日志**：每次工具调用都记下来。模型调错工具的排查全靠这个

---

## 12. 附录：API 速查

### MCP Server

```java
// 启动（STDIO）
McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(jsonMapper))
        .serverInfo("name", "1.0.0")
        .capabilities(ServerCapabilities.builder().tools(true).build())
        .toolCall(tool, handler)            // 便捷注册
        // 或 .tools(spec1, spec2)           // 用 SyncToolSpecification
        .validateToolInputs(true)           // 默认 true
        .build();

// 动态增删
server.addTool(spec);
server.removeTool("name");
server.notifyToolsListChanged();

// 关闭
server.closeGracefully();
```

### 定义工具

```java
McpSchema.Tool tool = McpSchema.Tool.builder("tool_name", inputSchemaMap)
        .description("做什么 + 什么时候用")
        .build();
```

### 工具 handler

```java
(exchange, request) -> {
    Map<String, Object> args = request.arguments();
    return McpSchema.CallToolResult.builder()
            .addTextContent("结果文本")
            .isError(false)
            .build();
}
```

### MCP Client

```java
McpSyncClient client = McpClient.sync(transport)
        .requestTimeout(Duration.ofSeconds(30))
        .initializationTimeout(Duration.ofSeconds(10))
        .capabilities(ClientCapabilities.builder().roots(true).sampling().build())
        .sampling(req -> ...)               // 支持 server 反向请求 LLM
        .build();

client.initialize();
client.getServerInfo();
client.getServerCapabilities();
client.listTools();
client.callTool(new McpSchema.CallToolRequest("name", Map.of(...)));
client.listResources();
client.readResource(resource);
client.closeGracefully();
```

### STDIO 传输

```java
// Server 侧
new StdioServerTransportProvider(jsonMapper)

// Client 侧
ServerParameters params = ServerParameters.builder("java")
        .args("-classpath", System.getProperty("java.class.path"), "com.example.MyServer")
        .build();
new StdioClientTransport(params, jsonMapper)
```

### Claude 侧（对比记忆）

| 概念 | MCP | Claude |
|---|---|---|
| 工具定义 | `McpSchema.Tool` | `com.anthropic.models.messages.Tool` |
| 调用请求 | `CallToolRequest(name, args)` | `ToolUseBlock`（模型返回） |
| 调用结果 | `CallToolResult` | `ToolResultBlockParam` |
| schema 载体 | `Map<String, Object>` | `Tool.InputSchema.Properties` |
| 完成信号 | — | `StopReason.END_TURN` |

### 官方资源

- MCP 规范：https://modelcontextprotocol.io
- Java SDK 文档：https://java.sdk.modelcontextprotocol.io
- Java SDK 仓库：https://github.com/modelcontextprotocol/java-sdk
- MCP Inspector（可视化调试工具）：`npx @modelcontextprotocol/inspector`
