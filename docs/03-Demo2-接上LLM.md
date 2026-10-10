# Demo 2 - 接上 LLM

> 本篇完整代码见文末 **附录：完整代码**（3 个文件全在）
> 涉及文件：`step3/McpToClaudeTool.java`、`step4/AgentMain.java`

---

## 一、这个 Demo 要做什么？

Demo 1 里，是你自己决定调 `add`、自己填了 `a=3, b=4`。

**这个 Demo 要让模型来做这件事。**

你问一句「北京天气怎么样？另外帮我算 1234 加 5678」，
模型自己决定：先调 `get_weather`，再调 `add`，然后把两个结果组织成一句话回答你。

### 1.1 只需要加两个类

```
Demo 1 已有：
  CalculatorServer  →  MCP Server（提供工具）
  ManualClient      →  MCP Client（人肉决定调什么）

Demo 2 新增：
  McpToClaudeTool   →  桥接层：MCP 工具 → Claude 工具格式   ← 只有 30 行
  AgentMain         →  agent loop：让模型来决策              ← 主角
```

**注意桥接层只有 30 行。** 这是本 Demo 第一个「啊哈时刻」——第三节会讲为什么。

### 1.2 跑起来是什么样

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

**注意看：模型在一轮里同时请求了两个工具**（两行 `[调用]` 挨着出现）。
它本可以分两轮做，这是它自己的选择。第二节会讲这意味着什么。

---

## 二、核心原理讲解

### 2.1 模型和你的「对话」长什么样

接上 LLM 之后，你和模型之间多了两种消息块：

```
┌──────────────────────────────────────────────────────────────┐
│  tool_use（模型发给你）                                       │
│  ─────────────────────────────────────────────────────────  │
│  {                                                            │
│    "type": "tool_use",                                        │
│    "id": "toolu_abc123",        ← 唯一 ID，回传时要带上       │
│    "name": "get_weather",                                     │
│    "input": { "city": "北京" }                                │
│  }                                                            │
│  「我想调 get_weather，参数是北京」                            │
└──────────────────────────────────────────────────────────────┘
                            ↓ 你去执行
┌──────────────────────────────────────────────────────────────┐
│  tool_result（你回给模型）                                    │
│  ─────────────────────────────────────────────────────────  │
│  {                                                            │
│    "type": "tool_result",                                     │
│    "tool_use_id": "toolu_abc123",   ← 必须对应上面那个 id      │
│    "content": "北京：多云，11°C",                             │
│    "is_error": false                                          │
│  }                                                            │
│  「查到了，结果是这个」                                        │
└──────────────────────────────────────────────────────────────┘
                            ↓ 模型继续推理
              要么再调一个工具，要么给出最终回答
```

**一个类比：**

就像你和一个看不见电脑屏幕的同事远程协作：

- 他说「帮我查一下北京的天气」→ `tool_use`
- 你查完告诉他「多云 11 度」→ `tool_result`
- 他说「那再帮我算个数」→ 又一个 `tool_use`
- 你算完告诉他 → 又一个 `tool_result`
- 他说「好，总结一下：…….」→ `end_turn`，对话结束

**他看不见你的屏幕，只能通过你的回报来推进工作。** 这就是 agent 的全部秘密。

### 2.2 ★ agent loop 本质就是个 while 循环

这是整个 Demo 里最需要理解的一句话：

> **所谓 agent，本质就是一个 while 循环。**

完整的循环长这样：

```
   用户提问
      │
      ▼
   ┌─────────────────────────────────────┐
   │  ① 把 MCP 工具清单转成 Claude tools，│
   │     连同问题一起发给 Claude           │
   └──────────────┬──────────────────────┘
                  ▼
   ┌─────────────────────────────────────┐
   │  ② Claude 返回 stop_reason=tool_use  │
   │     带着它想调的工具和参数            │
   └──────────────┬──────────────────────┘
                  ▼
   ┌─────────────────────────────────────┐
   │  ③ 拿着参数，通过 MCP Client          │
   │     调 MCP Server                    │
   └──────────────┬──────────────────────┘
                  ▼
   ┌─────────────────────────────────────┐
   │  ④ 把结果包成 tool_result 塞回对话，  │
   │     再发给 Claude                    │
   └──────────────┬──────────────────────┘
                  ▼
   ┌─────────────────────────────────────┐
   │  ⑤ Claude 看到结果，返回             │
   │     stop_reason=end_turn + 最终回答  │
   └──────────────┬──────────────────────┘
                  ▼
              循环结束
```

对应到代码就是一个 `for` 循环：

```java
for (int turn = 1; turn <= MAX_TURNS; turn++) {
    Message response = claude.messages().create(params.build());   // ① ②
    // ... 执行工具 ...
    if (toolResults.isEmpty()) return;                             // ⑤ 退出
    messages.add(工具结果);                                         // ③ ④
}
```

**没有更玄的东西。** 市面上所有「agent 框架」剥掉包装，核心都是这个循环。

### 2.3 桥接层为什么只有 30 行

现在回答第一个「啊哈时刻」。

把 MCP 工具转成 Claude 工具，本质是**把同一份 JSON Schema 从左口袋挪到右口袋**：

```
MCP 的 Tool.inputSchema      →  Map<String, Object>（一份 JSON Schema）
Claude 的 Tool.inputSchema   →  结构化的 Properties
```

所以代码短得离谱：

```java
public static Tool toClaudeTool(McpSchema.Tool mcpTool) {
    Map<String, Object> schema = mcpTool.inputSchema();

    // 搬 properties
    Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
    if (schema.get("properties") instanceof Map<?, ?> props) {
        props.forEach((k, v) -> propsBuilder.putAdditionalProperty(
                String.valueOf(k), JsonValue.from(v)));
    }

    // 搬 required
    Tool.InputSchema.Builder schemaBuilder = Tool.InputSchema.builder()
            .properties(propsBuilder.build());
    if (schema.get("required") instanceof List<?> required && !required.isEmpty()) {
        schemaBuilder.required(required.stream().map(String::valueOf).toList());
    }

    return Tool.builder()
            .name(mcpTool.name())
            .description(mcpTool.description() == null ? "" : mcpTool.description())
            .inputSchema(schemaBuilder.build())
            .build();
}
```

**为什么这么短？因为 MCP 和 Claude 用的是同一个东西：JSON Schema。**

MCP 没有发明新的 schema 语言，Claude 也没有。所以「转换」这个词其实有点夸张——
更准确的说法是「搬运」。

**这也再次印证了 Demo 1 的观点：MCP 和 function calling 是正交的两层，
所以连接它们只需要一层薄薄的胶水。**

### 2.4 ★ 六个必须做对的地方

这六点是整个 Demo 最值钱的部分。**错了不会报错，但会静默出问题。**

#### ① 用 `block.toParam()`，不要手搓

```java
assistantBlocks.add(block.toParam());
```

模型返回的 thinking 块带一个 `signature` 字段，是**密码学签名**，必须逐字节保留。
手动重建很容易破坏它，然后请求会报错。

Java SDK 提供了 `toParam()`，直接用。

#### ② 读 `content` 前先判断 `stop_reason`

模型可能因安全策略拒绝请求。**拒绝时 `content` 可能是空列表。**

```java
if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
    System.out.println("模型拒绝了本次请求。");
    response.stopDetails().ifPresent(d -> System.out.println("  类别: " + d.category()));
    return;
}
```

这是生产代码里最容易漏、且一漏就 NPE 的地方。

#### ③ 所有 `tool_result` 必须装在【同一条】user 消息里

```java
// ✅ 正确：一个 list，一条消息
messages.add(MessageParam.builder()
        .role(MessageParam.Role.USER)
        .contentOfBlockParams(toolResults)
        .build());
```

模型可以在一轮里并行请求多个工具（本 Demo 的输出就是），
这些结果必须合并成**一条** user 消息回传。

**拆成多条不会报错，但会悄悄训练模型不再并行调用工具。**
你只会发现延迟慢慢变高，很难联想到是这个原因。

#### ④ `is_error` 必须传

```java
.isError(Boolean.TRUE.equals(mcpResult.isError()))
```

工具报错时要显式标记。否则模型会把错误信息当成**正常返回值**，然后基于它继续编。

#### ⑤ 退出条件用 `toolResults.isEmpty()`，不要只看 `stopReason`

```java
if (toolResults.isEmpty()) return;
```

因为有些情况（模型既说话又想调工具）`stopReason` 会有歧义。
判断「这一轮到底有没有工具要执行」更稳。

#### ⑥ `maxTokens` 要给足

```java
.maxTokens(16000L)
```

Claude Opus 5 **默认开启思考**，而 `maxTokens` 是「思考 + 正文」的**总上限**。
给得太紧，回答会在半路被截断。

想降成本/延迟，调 `effort` 而不是砍 `maxTokens`：

```java
.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
```

---

## 三、完整代码实现

### 3.1 桥接层

完整代码见 **附录 A.1**。
核心逻辑已在 2.3 节列出，这里补充两个易错点：

```java
// ⚠️ 易错点 1：required 为空时不要调用 .required(List.of())
// 否则会序列化成 "required": []，虽然合法但没必要
if (rawRequired instanceof List<?> required && !required.isEmpty()) {
    schemaBuilder.required(...);
}

// ⚠️ 易错点 2：description 为 null 要兜底
// Claude 的 builder 不接受 null
.description(mcpTool.description() == null ? "" : mcpTool.description())
```

### 3.2 agent loop

完整代码见 **附录 A.2**。骨架：

```java
public final class AgentMain {

    private static final String MODEL = "claude-opus-5";
    private static final int MAX_TURNS = 10;   // 防御性上限

    public static void main(String[] args) {
        String question = ...;

        // 准备 A：起 MCP client（和 Demo 1 完全一样）
        McpSyncClient mcp = McpClient.sync(...).build();
        mcp.initialize();

        // 准备 B：起 Claude client（自动读 ANTHROPIC_API_KEY）
        AnthropicClient claude = AnthropicOkHttpClient.fromEnv();

        try {
            // ① 工具转换：MCP 发现 → Claude 格式
            List<Tool> claudeTools = mcp.listTools().tools().stream()
                    .map(McpToClaudeTool::toClaudeTool)
                    .toList();

            List<MessageParam> messages = new ArrayList<>();
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER).content(question).build());

            // ② agent loop
            for (int turn = 1; turn <= MAX_TURNS; turn++) {

                MessageCreateParams.Builder params = MessageCreateParams.builder()
                        .model(MODEL)
                        .maxTokens(16000L)          // 见 2.4 ⑥
                        .messages(messages);
                claudeTools.forEach(params::addTool);

                Message response = claude.messages().create(params.build());

                if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
                    ...                                     // 见 2.4 ②
                    return;
                }

                List<ContentBlockParam> assistantBlocks = new ArrayList<>();
                List<ContentBlockParam> toolResults = new ArrayList<>();

                for (ContentBlock block : response.content()) {
                    assistantBlocks.add(block.toParam());   // 见 2.4 ①

                    if (!block.isToolUse()) {
                        block.text().ifPresent(t -> System.out.println("Claude: " + t.text()));
                        continue;
                    }

                    // ③ 模型要调工具了 —— MCP 出场
                    ToolUseBlock toolUse = block.toolUse().orElseThrow();
                    Map<String, Object> mcpArgs = toolUse._input().convert(Map.class);

                    System.out.println("  [调用] " + toolUse.name() + " " + mcpArgs);

                    McpSchema.CallToolResult mcpResult = mcp.callTool(
                            new McpSchema.CallToolRequest(toolUse.name(), mcpArgs));

                    String resultText = extractText(mcpResult);
                    System.out.println("  [结果] " + resultText);

                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(resultText)
                                    .isError(Boolean.TRUE.equals(mcpResult.isError()))  // 见 2.4 ④
                                    .build()));
                }

                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(assistantBlocks).build());

                if (toolResults.isEmpty()) {                // 见 2.4 ⑤
                    System.out.println("(共 " + turn + " 轮)");
                    return;
                }

                messages.add(MessageParam.builder()         // 见 2.4 ③
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(toolResults).build());
            }

            System.out.println("达到最大轮数 " + MAX_TURNS + "，强制结束。");

        } finally {
            mcp.closeGracefully();
        }
    }
}
```

### 3.3 一个值得注意的设计

```java
int a = ((Number) request.arguments().get("a")).intValue();
```

对比一下两边参数的形状：

```
MCP 侧：   Map<String, Object>，值是 Object，要自己转型
Claude 侧：toolUse._input().convert(Map.class)，也是 Map<String, Object>
```

**两边都是「松散 Map」，都不可信。** 这也是为什么 Demo 1 里那道
自动参数校验很值钱——它替你挡住了模型可能生成的任何垃圾。

---

## 四、测试与验证

### 4.1 运行

```bash
# Windows cmd
set ANTHROPIC_API_KEY=sk-ant-...
mvn -q compile exec:exec -DmainClass=com.example.mcp.step4.AgentMain

# 带自定义问题（注意用 -DappArgs=，不是 -Dexec.args=）
mvn -q compile exec:exec -DmainClass=com.example.mcp.step4.AgentMain \
    -DappArgs="北京天气怎么样？另外帮我算 999 加 111"
```

### 4.2 预期输出

见本文开头 1.2 节。

### 4.3 ★ 一个很好的练手实验

用这个命令跑一次：

```bash
mvn -q compile exec:exec -DmainClass=com.example.mcp.step4.AgentMain \
    -DappArgs="帮我算 999 乘 111"
```

**注意：我们的 MCP Server 里根本没有乘法工具。**

观察模型会怎么做。它可能：

- 老实说「我没有乘法工具」
- 或者用加法硬凑（999×111 = 999×100 + 999×10 + 999×1……）

**这是个很好的练习**，能让你直观感受到**工具描述对模型行为的引导作用**。
如果你把 `add` 的描述改成「可以做加减乘除」，模型的行为会立刻不一样。

---

## 五、关键知识点总结

### 5.1 一句话回顾整个 Demo

> **所谓 agent，就是一个 while 循环：把工具清单给模型 → 模型说要调哪个 →
> 你去调 → 结果回传 → 直到模型不再要工具。**

### 5.2 完整链路图

```
┌──────────┐    tool_use     ┌──────────────┐   JSON-RPC   ┌────────────┐
│  Claude  │ ───────────────>│  你的代码     │ ────────────>│ MCP Server │
│          │                 │              │              │            │
│          │ <───────────────│  (agent loop)│ <────────────│  (工具)    │
└──────────┘   tool_result   └──────────────┘   结果        └────────────┘
     ↑                              │
     │                              │ 只在这一层做格式转换
     └──────────────────────────────┘ （30 行）
```

### 5.3 面试常见问题

**Q1：agent 的核心是什么？**

一个 while 循环。收到模型响应 → 检查有没有 `tool_use` → 有就执行并回传
`tool_result` → 再发一次 → 直到模型返回 `end_turn`。

所有 agent 框架剥掉包装都是这个循环。

**Q2：tool_use 和 tool_result 怎么对应？**

靠 ID。模型返回的 `tool_use` 块里有 `id`（形如 `toolu_abc123`），
你回传 `tool_result` 时必须带 `tool_use_id` 指向同一个 ID。

**漏掉任何一个 tool_use 对应的 tool_result，下一次请求会直接 400。**

**Q3：模型一轮里请求了多个工具，结果要怎么回传？**

**所有 `tool_result` 必须装在【同一条】user 消息里。**

拆成多条不会报错，但会**悄悄训练模型不再并行调用工具**——
你只会看到延迟慢慢变高，很难联想到原因。

**Q4：为什么调用工具失败时也要回 `tool_result`？**

两个原因：

1. 不回的话，对话会卡在「有个 tool_use 没有对应结果」的非法状态，下次请求 400
2. 要用 `is_error: true` 显式标记，否则模型会把错误信息当成**正常返回值**继续编

**Q5：`maxTokens` 为什么不能给太小？**

因为新版模型默认开启思考，而 `maxTokens` 是「思考 + 正文」的**总上限**。
给紧了回答会在半路被截断。

要控制成本应该调 `effort`（思考深度），而不是砍 `maxTokens`。

**Q6：为什么模型返回的 thinking 块不能手动重建？**

thinking 块带一个 `signature` 字段，是密码学签名，必须**逐字节原样保留**。
手动重建会破坏签名，导致请求报错。

Java SDK 提供 `block.toParam()` 做这个转换，直接用就行。

---

**下一节：** [Demo 3 - 只读数据库 MCP](./04-Demo3-只读数据库MCP.md)

---

## 附录：完整代码

> 这一篇涉及的文件都在下面，可以直接照着敲。
>
> 配套工程里 `src/` 下的同名文件是空的，填完可以对照。

### A.1 `McpToClaudeTool.java`

**路径**：`src/main/java/com/example/mcp/step3/McpToClaudeTool.java`

```java
package com.example.mcp.step3;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/**
 * 【第 3 步】桥接：把 MCP 工具翻译成 Claude 能看懂的工具。
 *
 * 这个类是整个教程里最"解气"的地方——你会发现它短得离谱。
 *
 * 为什么这么短？因为两边用的是同一样东西：JSON Schema。
 *   MCP 的 Tool.inputSchema      -> Map<String, Object>（一份 JSON Schema）
 *   Claude 的 Tool.inputSchema   -> 结构化的 Properties
 * 本质上只是把同一份 schema 从左口袋挪到右口袋。
 *
 * 这也解释了一个常见困惑："MCP 和 function calling 到底什么关系？"
 *   答：function calling 是模型侧的机制（模型输出一个 tool_use 表示想调用），
 *       MCP 是工具侧的机制（工具怎么被发现和调用）。
 *       两者正交，所以需要这样一层薄薄的胶水把它们接起来。
 */
public final class McpToClaudeTool {

    private McpToClaudeTool() {
    }

    @SuppressWarnings("unchecked")
    public static Tool toClaudeTool(McpSchema.Tool mcpTool) {
        Map<String, Object> schema = mcpTool.inputSchema();

        // --- properties ---
        Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
        Object rawProps = schema.get("properties");
        if (rawProps instanceof Map<?, ?> props) {
            props.forEach((key, value) ->
                    propsBuilder.putAdditionalProperty(String.valueOf(key), JsonValue.from(value)));
        }

        Tool.InputSchema.Builder schemaBuilder = Tool.InputSchema.builder()
                .properties(propsBuilder.build());

        // --- required ---
        // 注意：required 为空时不要调用 .required(List.of())，
        // 有些版本会把它序列化成 "required": []，虽然合法但没必要。
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
}
```

### A.2 `AgentMain.java`

**路径**：`src/main/java/com/example/mcp/step4/AgentMain.java`

```java
package com.example.mcp.step4;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;

import com.example.mcp.step3.McpToClaudeTool;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 【第 4 步】把上面三步串起来 —— 完整的 agent loop。
 *
 * 一次完整的交互长这样：
 *
 *   用户提问
 *      │
 *      ▼
 *   ① 把 MCP 工具清单转成 Claude 的 tools，连同问题一起发给 Claude
 *      │
 *      ▼
 *   ② Claude 返回 stop_reason = "tool_use"，带着它想调的工具和参数
 *      │
 *      ▼
 *   ③ 我们拿着这个参数，通过 MCP client 调 MCP server
 *      │
 *      ▼
 *   ④ 把结果包成 tool_result 塞回对话，再发给 Claude
 *      │
 *      ▼
 *   ⑤ Claude 看到结果，返回 stop_reason = "end_turn" 和最终回答
 *      │
 *      ▼
 *    循环结束
 *
 * 所谓 "agent"，本质就是这个 while 循环。没有更玄的东西。
 *
 * 运行（需要 ANTHROPIC_API_KEY）：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.step4.AgentMain
 *
 * 也可以带自定义问题：
 *   mvn -q compile exec:exec -DmainClass=com.example.mcp.step4.AgentMain \
 *       -Dexec.args="北京天气怎么样？另外帮我算 999 乘 111"   # 注意：没有乘法的工具，看它怎么办
 */
public final class AgentMain {

    /** 模型 ID。用当前最强的 Opus。 */
    private static final String MODEL = "claude-opus-5";

    /** 防御性上限：万一模型陷入循环，别让它无限烧 token。 */
    private static final int MAX_TURNS = 10;

    public static void main(String[] args) {
        // pom 里的 ${appArgs} 为空时会传进来一个空字符串，过滤掉
        String[] effective = java.util.Arrays.stream(args)
                .filter(a -> !a.isBlank())
                .toArray(String[]::new);

        String question = effective.length > 0
                ? String.join(" ", effective)
                : "北京天气怎么样？另外帮我算一下 1234 加 5678";

        System.out.println("用户: " + question);
        System.out.println("─".repeat(60));

        // ==================================================================
        // 准备阶段 A：起 MCP client（和 step2 完全一样）
        // ==================================================================
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();

        ServerParameters serverParams = ServerParameters.builder("java")
                .args("-Dfile.encoding=UTF-8",
                      "-Dstdout.encoding=UTF-8",
                      "-Dstderr.encoding=UTF-8",
                      "-classpath", System.getProperty("java.class.path"),
                      "com.example.mcp.step1.CalculatorServer")
                .build();

        McpSyncClient mcp = McpClient.sync(new StdioClientTransport(serverParams, jsonMapper))
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        // ==================================================================
        // 准备阶段 B：起 Claude client
        // 会自动读环境变量 ANTHROPIC_API_KEY
        // ==================================================================
        AnthropicClient claude = AnthropicOkHttpClient.fromEnv();

        try {
            mcp.initialize();

            // ==============================================================
            // ① 工具清单：MCP 发现 -> 翻译成 Claude 格式
            //
            // 这一步之后就再也不需要 mcp.listTools() 了。
            // 模型看到的是翻译后的 schema，它根本不知道背后是 MCP。
            // ==============================================================
            List<Tool> claudeTools = mcp.listTools().tools().stream()
                    .map(McpToClaudeTool::toClaudeTool)
                    .toList();

            System.out.println("已加载 " + claudeTools.size() + " 个 MCP 工具："
                    + claudeTools.stream().map(Tool::name).toList());
            System.out.println("─".repeat(60));

            // 对话历史。Claude API 是无状态的，每轮都要把完整历史发过去。
            List<MessageParam> messages = new ArrayList<>();
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(question)
                    .build());

            // ==============================================================
            // ② agent loop
            // ==============================================================
            for (int turn = 1; turn <= MAX_TURNS; turn++) {

                MessageCreateParams.Builder params = MessageCreateParams.builder()
                        .model(MODEL)
                        // maxTokens 是"思考 + 正文"的总上限。Opus 5 默认开启思考，
                        // 所以要给足余量，否则回答会在半路被截断。
                        .maxTokens(16000L)
                        // 想省钱/降延迟可以下调 effort，代价是推理深度：
                        // .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                        .messages(messages);

                claudeTools.forEach(params::addTool);

                Message response = claude.messages().create(params.build());

                // ---- 安全检查：模型可能因安全策略拒绝 ----
                // 拒绝时 content 可能是空的，下面读 content 前必须先判断。
                if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
                    System.out.println("模型拒绝了本次请求。");
                    response.stopDetails().ifPresent(d ->
                            System.out.println("  类别: " + d.category()));
                    return;
                }

                // ---- 收集这一轮的输出，同时执行工具 ----
                List<ContentBlockParam> assistantBlocks = new ArrayList<>();
                List<ContentBlockParam> toolResults = new ArrayList<>();

                for (ContentBlock block : response.content()) {
                    // toParam() 把响应块转成可以回传的参数块。
                    // 用官方提供的转换，不要手搓——thinking 块的 signature
                    // 必须原样保留，手动重建很容易踩雷。
                    assistantBlocks.add(block.toParam());

                    if (!block.isToolUse()) {
                        block.text().ifPresent(t -> System.out.println("Claude: " + t.text()));
                        continue;
                    }

                    // ======================================================
                    // ③ 模型想看某个工具了 —— 这里就是 MCP 出场的地方
                    // ======================================================
                    ToolUseBlock toolUse = block.toolUse().orElseThrow();
                    Map<String, Object> mcpArgs = toMap(toolUse);

                    System.out.println("  [调用] " + toolUse.name() + " " + mcpArgs);

                    // JsonValue -> Map，然后原样丢给 MCP client。
                    // 注意 model 给的参数没经过任何清洗，server 端的 schema
                    // 校验是唯一防线——这也是它默认开启的原因。
                    McpSchema.CallToolResult mcpResult = mcp.callTool(
                            new McpSchema.CallToolRequest(toolUse.name(), mcpArgs));

                    String resultText = extractText(mcpResult);
                    System.out.println("  [结果] " + resultText);

                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(resultText)
                                    // 工具报错时要显式标记 is_error，
                                    // 否则模型会把错误信息当成正常返回值继续往下编。
                                    .isError(Boolean.TRUE.equals(mcpResult.isError()))
                                    .build()));
                }

                // 把 assistant 这一轮说的话（含 tool_use 块）记进历史
                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(assistantBlocks)
                        .build());

                // ==============================================================
                // ⑤ 退出条件
                //
                // 没有工具要调，说明模型认为任务完成了。
                // 用 toolResults.isEmpty() 判断，比只看 stopReason 更稳——
                // 有些情况（比如它既说话又想调工具）stopReason 会有歧义。
                // ==============================================================
                if (toolResults.isEmpty()) {
                    System.out.println("─".repeat(60));
                    System.out.println("(共 " + turn + " 轮)");
                    return;
                }

                // ==============================================================
                // ④ 把工具结果回传，进入下一轮
                //
                // 注意：所有 tool_result 必须装在【同一条】 user 消息里。
                // 拆成多条会悄悄训练模型不再并行调用工具。
                // ==============================================================
                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(toolResults)
                        .build());
            }

            System.out.println("达到最大轮数 " + MAX_TURNS + "，强制结束。");

        } finally {
            mcp.closeGracefully();
        }
    }

    /** 把模型的工具入参转成普通 Map，好喂给 MCP。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(ToolUseBlock toolUse) {
        return toolUse._input().convert(Map.class);
    }

    /** CallToolResult.content() 是 Content 列表，文本在 TextContent 里。 */
    private static String extractText(McpSchema.CallToolResult result) {
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

### A.3 `McpAgent.java`

**路径**：`src/main/java/com/example/mcp/core/McpAgent.java`

```java
package com.example.mcp.core;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;

import com.example.mcp.step3.McpToClaudeTool;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 可复用的 agent loop：接一个 MCP Server，然后对话。
 *
 * 和 step4/AgentMain 的关系：
 *   step4/AgentMain 把循环写在 main 里，是为了教学——
 *   你可以在一屏内看完整条链路，不用跳文件。
 *   这个类是把同一套逻辑抽出来给实际项目用。
 *
 * 用法：
 *   try (McpAgent agent = McpAgent.connect("com.example.mcp.db.DbMcpServer")) {
 *       agent.ask("9 月份领用最多的备件是什么？");
 *   }
 */
public final class McpAgent implements AutoCloseable {

    private static final String MODEL = "claude-opus-5";

    /** 防御性上限：模型偶尔会陷入循环。 */
    private static final int MAX_TURNS = 12;

    /**
     * 回灌给模型的工具结果长度上限。
     *
     * 为什么需要这个？因为工具结果会原样进入对话历史，
     * 而对话历史每一轮都要完整重发。一个 200 行的表格塞进去，
     * 之后每一轮都在为它付 input token——成本会指数级放大。
     *
     * 真实项目里更常见的做法是：只回传摘要 + 让模型按需再查。
     */
    private static final int MAX_TOOL_RESULT_CHARS = 4_000;

    private final McpSyncClient mcp;
    private final List<Tool> tools;
    private final Consumer<String> out;

    /**
     * Claude 客户端【懒加载】。
     *
     * 这样 connect() 不需要 ANTHROPIC_API_KEY ——
     * 你可以先只连 MCP 自检（--dry-run），确认工具都加载对了，
     * 再去花 API 的钱。顺便也让"连不上 server"和"没配 Key"
     * 这两类错误分开了，排查起来清楚得多。
     */
    private AnthropicClient claude;

    /** 模型最近一次输出的正文，循环结束时返回。 */
    private String lastText = "";

    private McpAgent(McpSyncClient mcp, List<Tool> tools, Consumer<String> out) {
        this.mcp = mcp;
        this.tools = tools;
        this.out = out;
    }

    private AnthropicClient claude() {
        if (claude == null) {
            claude = AnthropicOkHttpClient.fromEnv();
        }
        return claude;
    }

    /**
     * 连上一个 MCP Server。
     *
     * @param serverMainClass server 的主类全名，比如 "com.example.mcp.db.DbMcpServer"。
     *                        这里刻意用「类名」而不是「命令」，是因为本教程的所有
     *                        server 都是同一个 Java 工程里的类。真实场景通常是
     *                        ServerParameters.builder("npx").args("-y", "@xxx/mcp-server")
     *                        这种形式——MCP 不关心 server 是什么语言写的。
     */
    public static McpAgent connect(String serverMainClass) {
        return connect(serverMainClass, System.out::println);
    }

    public static McpAgent connect(String serverMainClass, Consumer<String> out) {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();

        ServerParameters serverParams = ServerParameters.builder("java")
                .args("-Dfile.encoding=UTF-8",
                      "-Dstdout.encoding=UTF-8",
                      "-Dstderr.encoding=UTF-8",
                      "-classpath", System.getProperty("java.class.path"),
                      serverMainClass)
                .build();

        McpSyncClient mcp = McpClient.sync(new StdioClientTransport(serverParams, jsonMapper))
                // 数据库查询可能慢，超时给宽一点
                .requestTimeout(Duration.ofSeconds(60))
                .build();

        // 握手失败会在这里抛异常，越早失败越好
        mcp.initialize();

        // MCP 发现 → Claude 工具格式
        List<Tool> tools = mcp.listTools().tools().stream()
                .map(McpToClaudeTool::toClaudeTool)
                .toList();

        out.accept("已连接 " + mcp.getServerInfo().name()
                + "，加载 " + tools.size() + " 个工具："
                + tools.stream().map(Tool::name).toList());

        return new McpAgent(mcp, tools, out);
    }

    /**
     * 问一个问题，跑完整个 agent loop。
     *
     * @return 模型的最终回答；被拒绝或达到轮数上限时返回空串
     */
    public String ask(String question) {
        out.accept("");
        out.accept("用户: " + question);

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(question)
                .build());

        for (int turn = 1; turn <= MAX_TURNS; turn++) {

            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(MODEL)
                    // Claude Opus 5 默认开启思考，maxTokens 是「思考 + 正文」的总上限，
                    // 给紧会让回答半路被截断
                    .maxTokens(16000L)
                    .messages(messages);
            tools.forEach(params::addTool);

            Message response = claude().messages().create(params.build());

            // 安全策略可能拒绝。拒绝时 content 可能是空的，必须先判断再读。
            if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
                out.accept("[模型拒绝了本次请求]");
                response.stopDetails().ifPresent(d -> out.accept("  类别: " + d.category()));
                return "";
            }

            List<ContentBlockParam> assistantBlocks = new ArrayList<>();
            List<ContentBlockParam> toolResults = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                // 用官方 toParam()，不要手搓——thinking 块的 signature
                // 必须逐字节原样保留，手动重建会破坏它
                assistantBlocks.add(block.toParam());

                if (!block.isToolUse()) {
                    block.text().ifPresent(t -> finalTextHolder(out, t.text()));
                    continue;
                }

                ToolUseBlock toolUse = block.toolUse().orElseThrow();
                Map<String, Object> mcpArgs = toolUse._input().convert(Map.class);

                out.accept("  [调用] " + toolUse.name() + " " + summarizeArgs(mcpArgs));

                toolResults.add(invokeMcpTool(toolUse, mcpArgs));
            }

            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .contentOfBlockParams(assistantBlocks)
                    .build());

            // 没有工具要调 = 任务完成
            if (toolResults.isEmpty()) {
                out.accept("（共 " + turn + " 轮）");
                return lastText;
            }

            // 所有 tool_result 必须在同一条 user 消息里。
            // 拆成多条会悄悄训练模型不再并行调用工具。
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(toolResults)
                    .build());
        }

        out.accept("[达到最大轮数 " + MAX_TURNS + "，强制结束]");
        return lastText;
    }

    /** 已加载的工具名。用于自检，不需要调用模型。 */
    public List<String> toolNames() {
        return tools.stream().map(Tool::name).toList();
    }

    // ------------------------------------------------------------------

    private void finalTextHolder(Consumer<String> out, String text) {
        lastText = text;
        out.accept(text);
    }

    /** 调 MCP 工具，把结果包成 tool_result 块。 */
    private ContentBlockParam invokeMcpTool(ToolUseBlock toolUse, Map<String, Object> args) {
        try {
            McpSchema.CallToolResult r = mcp.callTool(
                    new McpSchema.CallToolRequest(toolUse.name(), args));

            String text = extractText(r);
            boolean isError = Boolean.TRUE.equals(r.isError());

            out.accept("  [" + (isError ? "错误" : "结果") + "] " + firstLines(text, 6));

            return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(toolUse.id())
                    .content(cap(text))
                    // 必须显式标记错误，否则模型会把报错当成正常返回值继续编
                    .isError(isError)
                    .build());

        } catch (Exception e) {
            // 工具本身崩了（server 挂了、超时了），也要回一条 tool_result，
            // 否则对话会卡在"有个 tool_use 没有对应结果"的非法状态
            String msg = "工具调用失败: " + e.getMessage();
            out.accept("  [错误] " + msg);
            return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(toolUse.id())
                    .content(msg)
                    .isError(true)
                    .build());
        }
    }

    private static String cap(String text) {
        if (text.length() <= MAX_TOOL_RESULT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_TOOL_RESULT_CHARS)
                + "\n…（结果过长已截断。如需完整数据，请缩小查询范围或使用聚合）";
    }

    private static String extractText(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : result.content()) {
            if (c instanceof McpSchema.TextContent t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    private static String summarizeArgs(Map<String, Object> args) {
        String s = String.valueOf(args);
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static String firstLines(String text, int n) {
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, lines.length); i++) {
            if (!lines[i].isBlank()) {
                sb.append(lines[i].strip());
                if (i < n - 1 && i < lines.length - 1) {
                    sb.append(" / ");
                }
            }
        }
        String s = sb.toString();
        return s.length() > 300 ? s.substring(0, 300) + "…"
                : (lines.length > n ? s + " …（共 " + lines.length + " 行）" : s);
    }

    @Override
    public void close() {
        // 不调用的话，server 子进程会赖着不走
        mcp.closeGracefully();
    }
}
```
