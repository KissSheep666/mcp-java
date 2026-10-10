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
