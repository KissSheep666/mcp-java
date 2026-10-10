# Demo 1 - 最小 MCP 链路

> 本篇完整代码见文末 **附录：完整代码**（3 个文件全在）
> 涉及文件：`step1/CalculatorServer.java`、`step2/ManualClient.java`

---

## 一、这个 Demo 要做什么？

写两个 Java 类：

1. **一个 MCP Server** —— 暴露两个工具：`add`（加法）和 `get_weather`（查天气）
2. **一个 MCP Client** —— 连上它，列出工具，调用工具

**总代码量不到 300 行，但它是整个 MCP 的地基。**

### 1.1 ★ 最重要的一点：这个 Demo 里没有 LLM

请把这句话读三遍：

> **Demo 1 完全没有 Claude，也没有任何大模型。**

是你自己决定要调 `add`，是你自己填了 `a=3, b=4`：

```java
client.callTool(new McpSchema.CallToolRequest("add", Map.of("a", 3, "b", 4)));
```

**为什么故意这么设计？**

因为几乎所有人第一次接触 MCP 都会把它和 function calling 搞混。
把 LLM 拿掉，这个混淆就自然消失了——你会亲眼看到
**MCP 是一个纯粹的「程序调工具」协议**。

等 Demo 2 再把 Claude 接进来，你就会清楚地看到：
原来「接 LLM」只是在这条链路外面包了一层循环而已。

### 1.2 跑起来是什么样

```
已连接: calc-server v1.0.0
server 能力: ServerCapabilities[..., tools=ToolCapabilities[listChanged=true]]

=== tools/list ===
• add
  描述  : 计算两个整数之和。当用户询问加法运算时使用。
  schema: {required=[a, b], type=object, properties={...}}
• get_weather
  描述  : 查询指定城市的当前天气。当用户询问天气时使用。
  schema: {required=[city], type=object, properties={...}}

=== tools/call ===
add(3, 4)          -> 3 + 4 = 7
get_weather(北京)  -> 北京：多云，11°C

=== 故意传错参数（a 传字符串）===
isError = true
content = Tool (add) input validation failed: ... [/a: 已找到 string，必须是 integer]
```

最后那段是彩蛋，第二节会讲。

---

## 二、核心原理讲解

### 2.1 MCP Server 的三个要素

一个 MCP Server 本质上就是回答两个问题：

> **「我有什么工具？」** 和 **「怎么用这些工具？」**

对应到代码，就是三样东西：

```
┌──────────────────────────────────────────────────────────┐
│  ① Tool（工具声明）                                       │
│     名字 + 描述 + 入参的 JSON Schema                      │
│     → 回答"我有什么工具"                                  │
├──────────────────────────────────────────────────────────┤
│  ② Handler（处理器）                                      │
│     收到参数，干活，返回结果                              │
│     → 回答"怎么用"                                        │
├──────────────────────────────────────────────────────────┤
│  ③ Capabilities（能力声明）                               │
│     告诉客户端"我提供 tools / resources / prompts"        │
│     → 决定客户端会不会来问                                │
└──────────────────────────────────────────────────────────┘
```

**一个类比：**

MCP Server 就像一家餐厅的菜单：

- **Tool** = 菜名 + 配料表（顾客知道有什么、由什么组成）
- **Description** = 菜品介绍（顾客靠它决定点不点）
- **Handler** = 后厨（真正做菜的地方）
- **Capabilities** = 门口挂的招牌（写着「本店供应炒菜」，顾客才会进来）

**★ 其中 Description 比你想的重要得多。**

```java
// ✅ 好
.description("计算两个整数之和。当用户询问加法运算时使用。")

// ❌ 差
.description("加法工具")
```

在 Demo 1 里这个差别还不明显（因为是你自己决定调什么）。
但到了 Demo 2，**模型完全靠这句话判断「什么时候该调这个工具」**。

它看不到你的代码，只有这一句话。

### 2.2 协议底层：其实是 JSON-RPC 2.0

MCP 没有重新发明 RPC，它就是 **JSON-RPC 2.0** 加一套约定好的方法名。

上面那次 `add(3, 4)` 调用，在网线上长这样：

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

**就这么朴素。** 理解了这一点，SDK 的所有 API 都不再有魔法。

常用的方法名就四个：


| 方法 | 作用 | 类比 |
| --- | --- | --- |
| `initialize` | 握手，交换身份和能力 | 交换名片 |
| `tools/list` | 列出所有工具 | 看菜单 |
| `tools/call` | 调用某个工具 | 点菜 |
| `notifications/initialized` | 握手完成的通知 | 「名片收好了」 |

### 2.3 生命周期：一次完整的会话

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
  │──── 关闭连接 ─────────────────────────────────>│
```

**为什么要有握手这一步？**

因为 MCP 是**双向**协议，而不只是「客户端问、服务端答」。

Server 也可能需要反向请求 Client 做事，比如：

- **Sampling**：Server 说「帮我调一次 LLM」——这样 Server 自己不用内置模型
- **Elicitation**：Server 说「帮我问用户一个问题」

所以双方必须先声明「我支持哪些能力」，**只有双方都声明支持的能力才能用**。

这就是为什么 `CalculatorServer` 里要写这行：

```java
.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
```

**不声明的话，客户端根本不会来调 `tools/list`。**

> 💡 **想亲眼看握手过程？**
> 把 `src/main/resources/simplelogger.properties` 里的日志级别改成 `info` 再跑一次，
> 你能看到完整的协议版本协商和能力交换。

### 2.4 传输：为什么用 STDIO

MCP 有两种主流传输：


| 传输 | 场景 | 怎么工作 | 类比 |
| --- | --- | --- | --- |
| **STDIO** | 本地子进程 | 客户端 fork 出 server，用 stdin/stdout 通信 | 面对面说话 |
| **Streamable HTTP** | 远程服务 | 一个 HTTP endpoint | 打电话 |

**本教程用 STDIO，因为：**

- 零网络配置，不用起服务、不用配端口
- 天然安全（本地进程，不暴露端口）
- 部署简单（一个 jar 就够）

**但 STDIO 有个必须记住的铁律：**

> ⚠️ **stdout 被 JSON-RPC 协议独占。任何 `System.out.println` 都会污染协议流。**

这就是「五个坑」里的头号杀手。日志必须走 stderr。

**另一个关键设计：客户端怎么知道 server 是干嘛的？**

```java
ServerParameters.builder("java")
    .args("-classpath", System.getProperty("java.class.path"),
          "com.example.mcp.step1.CalculatorServer")
    .build();
```

**注意这里描述的是「怎么启动一个进程」，而不是「调用哪个类」。**

因为 MCP 的设计目标就是**语言无关**。换成 Python 写的 server 就是：

```java
ServerParameters.builder("python").args("-m", "my_server").build()
```

换成 Node：

```java
ServerParameters.builder("npx").args("-y", "@some/mcp-server").build()
```

**客户端完全不需要知道对面是什么语言写的。** 这是整个协议最值钱的地方。

---

## 三、完整代码实现

### 3.1 项目配置

见 [`全量代码.md`](./全量代码.md) 第 1.1 节（`pom.xml`）。
Demo 1 只需要 MCP SDK 和 slf4j，暂时用不到 anthropic-java。

### 3.2 MCP Server

完整代码见 **附录 A.1**。核心结构：

```java
public final class CalculatorServer {

    public static void main(String[] args) {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();

        // ① 定义两个工具的 JSON Schema
        Map<String, Object> addSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "a", Map.of("type", "integer", "description", "第一个加数"),
                        "b", Map.of("type", "integer", "description", "第二个加数")
                ),
                "required", List.of("a", "b")
        );

        // ② 声明工具
        McpSchema.Tool addTool = McpSchema.Tool.builder("add", addSchema)
                .description("计算两个整数之和。当用户询问加法运算时使用。")
                .build();

        // ③ 组装并启动
        McpSyncServer server = McpServer.sync(lifecycle.provider())
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

        lifecycle.awaitClientDisconnect();
        server.closeGracefully();
    }
}
```

**四个值得停下来看的点：**

**① JSON Schema 就是普通的 JSON Schema**

MCP 没有发明新的 schema 语言，直接复用 JSON Schema。
记住这一点——**Demo 2 里把 MCP 工具转成 Claude 工具之所以只要 30 行，就是因为两边用的是同一个东西。**

**② `toolCall` 的 lambda 有两个参数**

```java
.toolCall(addTool, (exchange, request) -> { ... })
```

- `request` —— 含 `name()` 和 `arguments()`，就是调用方填的参数
- `exchange` —— 用来**反向**跟客户端通信（Demo 1 用不到，但它是 MCP 比「普通函数注册」强的地方）

**③ 参数取出来是 `Number` 不是 `int`**

```java
int a = ((Number) request.arguments().get("a")).intValue();
```

因为 `arguments()` 是 `Map<String, Object>`，JSON 数字反序列化后是 `Integer` 或 `Long`。
直接强转 `(int)` 会 ClassCastException。

**④ 结尾的 `lifecycle.awaitClientDisconnect()`**

这不是可有可无的收尾。stdio server **在 stdin 关闭后不会自己退出**，
用 `new CountDownLatch(1).await()` 永久阻塞的话，客户端一崩你就攒一堆孤儿进程。

### 3.3 MCP Client

完整代码见 **附录 A.2**。核心五步：

```java
// ① 描述"怎么启动 server"
ServerParameters serverParams = ServerParameters.builder("java")
        .args("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
              "-classpath", System.getProperty("java.class.path"),
              "com.example.mcp.step1.CalculatorServer")
        .build();

// ② 建立连接
McpSyncClient client = McpClient.sync(new StdioClientTransport(serverParams, jsonMapper))
        .requestTimeout(Duration.ofSeconds(30))
        .build();

// ③ 握手
client.initialize();
client.getServerInfo();          // Implementation[name=calc-server, version=1.0.0]
client.getServerCapabilities();  // 对方支持什么

// ④ 发现工具
McpSchema.ListToolsResult tools = client.listTools();
for (McpSchema.Tool t : tools.tools()) {
    System.out.println(t.name() + " : " + t.description());
    System.out.println("  schema: " + t.inputSchema());
}

// ⑤ 调用工具
McpSchema.CallToolResult r = client.callTool(
        new McpSchema.CallToolRequest("add", Map.of("a", 3, "b", 4)));

// 必须优雅关闭，否则 server 子进程会赖着不走
client.closeGracefully();
```

**注意第 ④ 步打印出来的东西。**

它和 `CalculatorServer` 里声明的 `name` / `description` / `inputSchema` **一模一样**，
原样传过来的。

在 Demo 1 里，它的用途是「让调用方知道能干什么」。
**在 Demo 2 里你会看到，它恰好就是喂给 Claude 的工具清单——同一个返回值，两个用途。**

---

## 四、测试与验证

### 4.1 运行

```bash
mvn -q compile exec:exec -DmainClass=com.example.mcp.step2.ManualClient
```

### 4.2 预期输出

见本文开头 1.2 节。

### 4.3 重点看最后一段：参数校验

Demo 里故意调了一次 `add`，把 `a` 传成字符串：

```java
client.callTool(new McpSchema.CallToolRequest("add", Map.of("a", "不是数字", "b", 1)));
```

输出：

```
isError = true
content = Tool (add) input validation failed: Validation failed:
          JSON schema validation errors: [/a: 已找到 string，必须是 integer]
```

**这说明：SDK 默认会按 `inputSchema` 校验入参，不合法直接返回 `isError=true`，根本不进 handler。**

这是一道免费的安全网。想关掉（不推荐）：server 端 `.validateToolInputs(false)`。

**为什么说它是「免费」的？** 因为在 Demo 2 接上模型之后，参数就变成**模型生成的**了，
可能是任何东西。有这道校验，你至少不用自己写参数校验逻辑。

---

## 五、关键知识点总结

### 5.1 一句话回顾整个 Demo

> **MCP Client 通过 STDIO 启动一个 MCP Server 子进程，
> 握手交换能力，然后用 JSON-RPC 调用它暴露的工具。全程不涉及任何 LLM。**

### 5.2 架构速查

```
┌─────────────── Host（你的程序）───────────────┐
│                                                │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐   │
│  │ Client A │   │ Client B │   │ Client C │   │  ← Client 和 Server 是 1:1
│  └────┬─────┘   └────┬─────┘   └────┬─────┘   │
└───────┼──────────────┼──────────────┼─────────┘
        │              │              │
   ┌────▼────┐    ┌────▼────┐    ┌────▼────┐
   │Server 1 │    │Server 2 │    │Server 3 │
   │(文件系统)│    │(数据库)  │    │(自研)   │
   └─────────┘    └─────────┘    └─────────┘
```

**一个 Host 持有多个 Client，每个 Client 连一个 Server。**
不是「一个 Client 连多个 Server」——这是新手最常画错的图。

### 5.3 面试常见问题

**Q1：MCP 和 function calling 有什么区别？**

它们是正交的两层，不是竞品。

- **Function calling** 是模型侧的机制：模型输出 `tool_use` 块，表示「我想调用某个工具」
- **MCP** 是工具侧的机制：工具怎么被发现（`tools/list`）、怎么被调用（`tools/call`）

两个可以独立存在：MCP Server 可以被完全没有 LLM 的程序使用（本 Demo 就是）；
function calling 也可以调用完全不走 MCP 的、你手写的工具。

**Q2：MCP 的通信协议是什么？**

底层是 **JSON-RPC 2.0**。MCP 没有重新发明 RPC，只是约定了一套方法名
（`initialize` / `tools/list` / `tools/call` 等）和消息结构。

**Q3：MCP 有哪几种传输方式？怎么选？**

- **STDIO**：本地子进程，客户端 fork server，用 stdin/stdout 通信。部署简单、天然安全。
- **Streamable HTTP**：远程服务，一个 HTTP endpoint。

判断标准很简单：server 和 host 在同一台机器上 → STDIO；server 独立部署 → Streamable HTTP。

（历史上的 HTTP+SSE 传输**已废弃**，新项目不要用。）

**Q4：为什么 MCP 要有握手（initialize）这一步？**

因为 MCP 是**双向**协议，Server 也可能反向请求 Client（比如 sampling、elicitation）。
双方必须先声明自己支持哪些能力，**只有双方都声明支持的能力才能用**。

**Q5：STDIO 传输最容易踩的坑是什么？**

**stdout 被污染。** stdout 是 JSON-RPC 的独占通道，任何
`System.out.println`、日志框架的默认 stdout 输出、依赖库的 print，
都会破坏协议流，导致客户端解析失败或直接卡死。

解法：日志统一走 stderr。

---

**下一节：** [Demo 2 - 接上 LLM](./03-Demo2-接上LLM.md)

---

## 附录：完整代码

> 这一篇涉及的文件都在下面，可以直接照着敲。
>
> 配套工程里 `src/` 下的同名文件是空的，填完可以对照。

### A.1 `CalculatorServer.java`

**路径**：`src/main/java/com/example/mcp/step1/CalculatorServer.java`

```java
package com.example.mcp.step1;

import com.example.mcp.core.StdioLifecycle;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/**
 * 【第 1 步】一个最小可用的 MCP Server。
 *
 * 关键认知：这个类里完全没有 LLM。
 * MCP Server 不知道 Claude 的存在，它只是一个"能被标准协议调用的工具集合"。
 * 任何 MCP 客户端（Claude Desktop、IDE、你自己写的程序）都能调它。
 *
 * 运行方式（server 不需要参数，它通过 stdin/stdout 通信）：
 *   mvn -q exec:exec -DmainClass=com.example.mcp.step1.CalculatorServer
 *
 * 但你不会想手动跑它——它是被客户端以子进程方式拉起来的。
 * 想单独验证，见 step2 的 ManualClient。
 */
public final class CalculatorServer {

    public static void main(String[] args) {
        // JSON 序列化实现。SDK 通过 SPI 找到 Jackson3 的实现，
        // 这个 Supplier 就是官方给的默认入口。
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();

        // ------------------------------------------------------------------
        // 1) 用 JSON Schema 描述工具的入参
        //
        // 注意：这就是一份普通的 JSON Schema。MCP 没有发明新的 schema 语言，
        // 它直接复用 JSON Schema。这一点很重要——后面接 Claude 时，
        // 这份 schema 几乎可以原样转成 Claude 的 tool.input_schema。
        // ------------------------------------------------------------------
        Map<String, Object> addSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "a", Map.of("type", "integer", "description", "第一个加数"),
                        "b", Map.of("type", "integer", "description", "第二个加数")
                ),
                "required", List.of("a", "b")
        );

        Map<String, Object> weatherSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of("type", "string", "description", "城市名，例如：北京")
                ),
                "required", List.of("city")
        );

        // ------------------------------------------------------------------
        // 2) 声明工具
        //
        // description 极其重要：模型就是靠它决定"什么时候该调这个工具"。
        // 写清楚"做什么"+"什么时候用"，比写清楚参数更重要。
        // ------------------------------------------------------------------
        McpSchema.Tool addTool = McpSchema.Tool.builder("add", addSchema)
                .description("计算两个整数之和。当用户询问加法运算时使用。")
                .build();

        McpSchema.Tool weatherTool = McpSchema.Tool.builder("get_weather", weatherSchema)
                .description("查询指定城市的当前天气。当用户询问天气时使用。")
                .build();

        // ------------------------------------------------------------------
        // 3) 组装并启动 server
        //
        // StdioServerTransportProvider：用标准输入/输出收发 JSON-RPC 消息。
        // 这也是为什么绝对不能用 System.out.println 打日志——会污染协议流。
        // ------------------------------------------------------------------
        StdioLifecycle.Handle lifecycle = StdioLifecycle.create(jsonMapper);

        McpSyncServer server = McpServer.sync(lifecycle.provider())
                // 名字和版本会在 initialize 握手时告诉客户端
                .serverInfo("calc-server", "1.0.0")
                // 能力协商：我提供 tools。客户端据此知道可以调 tools/list、tools/call
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())

                // toolCall 是注册工具的便捷写法，等价于构造一个 SyncToolSpecification。
                // lambda 的两个参数：
                //   exchange —— 可用于反向请求客户端（比如 sampling，让它帮忙调 LLM）
                //   request  —— 内含 name() 和 arguments()，arguments() 就是模型填的参数
                .toolCall(addTool, (exchange, request) -> {
                    int a = ((Number) request.arguments().get("a")).intValue();
                    int b = ((Number) request.arguments().get("b")).intValue();
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(a + " + " + b + " = " + (a + b))
                            .build();
                })

                .toolCall(weatherTool, (exchange, request) -> {
                    String city = String.valueOf(request.arguments().get("city"));
                    // 假的天气数据，用城市名做确定性哈希，保证每次演示结果一致
                    String[] conditions = {"晴", "多云", "小雨", "阴"};
                    int hash = Math.abs(city.hashCode());
                    String condition = conditions[hash % conditions.length];
                    int temp = 10 + hash % 20;
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(city + "：%s，%d°C".formatted(condition, temp))
                            .build();
                })

                .build();

        // 日志走 stderr。用 System.err 而不是 logger 只是为了让这个例子零配置可跑。
        System.err.println("[calc-server] 已启动，等待 stdin 上的 JSON-RPC 消息...");

        // 阻塞直到客户端断开（stdin EOF），然后干净退出。
        // 不要用 `new CountDownLatch(1).await()` 永久阻塞——
        // 那样客户端崩了之后 server 会变成孤儿进程。
        lifecycle.awaitClientDisconnect();
        try {
            server.closeGracefully();
        } catch (Exception ignored) {
            // 已经关了，正常
        }
    }
}
```

### A.2 `ManualClient.java`

**路径**：`src/main/java/com/example/mcp/step2/ManualClient.java`

```java
package com.example.mcp.step2;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.Map;

/**
 * 【第 2 步】一个纯 MCP 客户端 —— 注意，这里依然没有 LLM。
 *
 * 这是整个教程里最重要的一步，因为它证明了一件事：
 *   MCP 是"程序调用工具"的协议，不是"模型调用工具"的协议。
 *
 * 第 3、4 步才把 Claude 接进来，让模型来决定调哪个工具。
 * 把这两件事分开理解，你就不会再把 MCP 和 function calling 搞混。
 *
 * 运行：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.step2.ManualClient
 */
public final class ManualClient {

    public static void main(String[] args) {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();

        // ------------------------------------------------------------------
        // 1) 描述"怎么启动 server"
        //
        // 这就是 MCP 的核心设计：客户端不关心 server 是 Java、Python 还是 Node 写的，
        // 只关心"用什么命令能把它拉起来，然后用 stdio 说 JSON-RPC"。
        // 一个 Python 写的 server，这里就换成 ("python", "-m", "my_server")。
        // ------------------------------------------------------------------
        ServerParameters serverParams = ServerParameters.builder("java")
                // 关键：复用当前进程的 classpath，子进程才能找到 CalculatorServer。
                // 编码参数同样要带上，否则子进程打的日志会是乱码（见 pom.xml 里的说明）。
                .args("-Dfile.encoding=UTF-8",
                      "-Dstdout.encoding=UTF-8",
                      "-Dstderr.encoding=UTF-8",
                      "-classpath", System.getProperty("java.class.path"),
                      "com.example.mcp.step1.CalculatorServer")
                .build();

        // ------------------------------------------------------------------
        // 2) 建立连接
        //
        // StdioClientTransport 会在第一次通信时真正 fork 出 server 子进程。
        // MCP 有两种主流传输：STDIO（本地子进程）和 Streamable HTTP（远程服务）。
        // 旧的 HTTP+SSE 传输已废弃，新项目别再用。
        // ------------------------------------------------------------------
        McpSyncClient client = McpClient.sync(new StdioClientTransport(serverParams, jsonMapper))
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        try {
            // ------------------------------------------------------------------
            // 3) 握手：initialize
            //
            // 这一步会交换双方的身份和能力：
            //   客户端说：我支持 roots / sampling / elicitation
            //   server 说：我提供 tools / resources / prompts
            // 协商完成后，双方才知道接下来能发什么请求。
            // ------------------------------------------------------------------
            client.initialize();

            McpSchema.Implementation info = client.getServerInfo();
            System.out.println("已连接: " + info.name() + " v" + info.version());
            System.out.println("server 能力: " + client.getServerCapabilities());
            System.out.println();

            // ------------------------------------------------------------------
            // 4) tools/list —— 发现工具有哪些
            //
            // 返回的就是 step1 里声明的 name / description / inputSchema。
            // 在没有 LLM 的世界里，这一步的用途是让调用方（人或程序）知道能干什么。
            // 在第 3 步你会看到，它恰好就是喂给 Claude 的工具清单。
            // ------------------------------------------------------------------
            System.out.println("=== tools/list ===");
            McpSchema.ListToolsResult tools = client.listTools();
            for (McpSchema.Tool tool : tools.tools()) {
                System.out.println("• " + tool.name());
                System.out.println("  描述  : " + tool.description());
                System.out.println("  schema: " + tool.inputSchema());
            }
            System.out.println();

            // ------------------------------------------------------------------
            // 5) tools/call —— 真正调用
            //
            // 注意参数是一个普通 Map。没有魔法，没有模型，
            // 就是我们自己决定要调 add，自己填了 a=3, b=4。
            // ------------------------------------------------------------------
            System.out.println("=== tools/call ===");
            McpSchema.CallToolResult r1 = client.callTool(
                    new McpSchema.CallToolRequest("add", Map.of("a", 3, "b", 4)));
            System.out.println("add(3, 4)          -> " + text(r1));

            McpSchema.CallToolResult r2 = client.callTool(
                    new McpSchema.CallToolRequest("get_weather", Map.of("city", "北京")));
            System.out.println("get_weather(北京)  -> " + text(r2));

            // ------------------------------------------------------------------
            // 6) 服务端校验：故意传错参数类型
            //
            // SDK 默认会按 inputSchema 校验入参，不合法就返回 isError=true，
            // 根本不进 handler。这是一个容易被忽略但很有用的安全网。
            // 想关掉：server 端 .validateToolInputs(false)
            // ------------------------------------------------------------------
            System.out.println();
            System.out.println("=== 故意传错参数（a 传字符串）===");
            McpSchema.CallToolResult bad = client.callTool(
                    new McpSchema.CallToolRequest("add", Map.of("a", "不是数字", "b", 1)));
            System.out.println("isError = " + bad.isError());
            System.out.println("content = " + text(bad));

        } finally {
            // 必须优雅关闭：它会终止 fork 出来的 server 子进程。
            // 漏掉这行最典型的症状是跑完一次后，一堆 java 进程赖着不走。
            client.closeGracefully();
        }
    }

    /** CallToolResult.content() 是 Content 列表，文本在 TextContent 里。 */
    private static String text(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : result.content()) {
            if (c instanceof McpSchema.TextContent t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
```

### A.3 `StdioLifecycle.java`

**路径**：`src/main/java/com/example/mcp/core/StdioLifecycle.java`

```java
package com.example.mcp.core;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

/**
 * STDIO server 的生命周期管理。
 *
 * ══════════════════════════════════════════════════════════════════
 *  解决什么问题
 * ══════════════════════════════════════════════════════════════════
 *
 * MCP 官方 SDK 的 stdio server 有个容易被忽略的行为：
 * **主线程一旦挂住就永远不退出，即使 stdin 已经关闭。**
 *
 * 后果有两个：
 *
 *   1. 孤儿进程。客户端崩了、被 kill 了、没调 closeGracefully()，
 *      server 进程会一直赖在任务管理器里。跑几十次就堆一堆。
 *
 *   2. 退出码非零。客户端只能强杀进程，Windows 上强杀的退出码是 1，
 *      于是客户端每次都打印 "MCP server process failed with code 1" ——
 *      明明是一次正常关闭，看起来却像崩溃。
 *
 * 根因是：SDK 没有暴露"会话已关闭"的回调，
 * 而 server 的 main 通常写成 `new CountDownLatch(1).await()`（永久阻塞）。
 *
 * ══════════════════════════════════════════════════════════════════
 *  思路
 * ══════════════════════════════════════════════════════════════════
 *
 * 既然 SDK 不给回调，就自己造一个信号：
 * 把 System.in 包一层，一旦读到 EOF（-1）就释放 latch。
 *
 * stdin 关闭 == 客户端已断开，这是 stdio 传输下最可靠的断连信号。
 *
 * StdioServerTransportProvider 正好有个接收自定义流的构造函数，
 * 所以这个包装可以无侵入地塞进去。
 */
public final class StdioLifecycle {

    private StdioLifecycle() {
    }

    /**
     * 创建一个带断连感知的传输。
     */
    public static Handle create(McpJsonMapper jsonMapper) {
        CountDownLatch eof = new CountDownLatch(1);
        McpServerTransportProvider provider = new StdioServerTransportProvider(
                jsonMapper,
                new EofAwareInputStream(System.in, eof),
                System.out);
        return new Handle(provider, eof);
    }

    public record Handle(McpServerTransportProvider provider, CountDownLatch eof) {

        /**
         * 阻塞，直到客户端断开（stdin EOF）。
         *
         * 返回后应该调用 server.closeGracefully() 再让 main 结束，
         * 这样 JVM 会以退出码 0 正常退出。
         */
        public void awaitClientDisconnect() {
            try {
                eof.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 转发所有读取，顺手盯住 EOF。
     *
     * 注意要覆盖 read() 和 read(byte[], int, int) 两个重载——
     * 具体走哪个取决于调用方，漏掉一个就检测不到 EOF。
     */
    private static final class EofAwareInputStream extends FilterInputStream {

        private final CountDownLatch eof;

        EofAwareInputStream(InputStream in, CountDownLatch eof) {
            super(in);
            this.eof = eof;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b == -1) {
                eof.countDown();
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n == -1) {
                eof.countDown();
            }
            return n;
        }
    }
}
```
