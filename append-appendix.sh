#!/usr/bin/env bash
# 把各篇对应的完整代码追加为「附录」，并改写正文里的跳转指向。
# 幂等：重复运行会先删掉旧的附录再重加。
set -euo pipefail

REF=".reference/java/com/example/mcp"
DOCS="docs"

# append_appendix <文档> <该篇的文件列表（相对 mcp/ 的路径）>
append_appendix() {
  local doc="$1"; shift
  local files=("$@")

  # 先删掉已有的附录（从 "## 附录：完整代码" 到文件末尾）
  if grep -q '^## 附录：完整代码' "$doc"; then
    sed -i '/^## 附录：完整代码$/,$d' "$doc"
    # 去掉残留的分隔线和空行
    sed -i -e :a -e '/^\n*$/{$d;N;ba' -e '}' "$doc"
  fi

  {
    printf '\n---\n\n## 附录：完整代码\n\n'
    printf '> 这一篇涉及的文件都在下面，可以直接照着敲。\n>\n'
    printf '> 配套工程里 `src/` 下的同名文件是空的，填完可以对照。\n'

    local n=0
    for f in "${files[@]}"; do
      n=$((n + 1))
      local src="$REF/$f"
      [ -f "$src" ] || { echo "  ! 缺文件: $f" >&2; continue; }
      printf '\n### A.%s `%s`\n\n' "$n" "$(basename "$f")"
      printf '**路径**：`src/main/java/com/example/mcp/%s`\n\n' "$f"
      printf '```java\n'
      cat "$src"
      printf '```\n'
    done
  } >> "$doc"
}

# ---- 各篇对应的文件 ----
append_appendix "$DOCS/02-Demo1-最小MCP链路.md" \
  "step1/CalculatorServer.java" "step2/ManualClient.java" "core/StdioLifecycle.java"

append_appendix "$DOCS/03-Demo2-接上LLM.md" \
  "step3/McpToClaudeTool.java" "step4/AgentMain.java" "core/McpAgent.java"

append_appendix "$DOCS/04-Demo3-只读数据库MCP.md" \
  "db/Db.java" "db/SchemaIntrospector.java" "db/DbMcpServer.java" "db/DbAgentMain.java"

append_appendix "$DOCS/05-Demo4-安全与加固.md" \
  "db/SqlGuard.java" "db/AuditLog.java" "db/DbClientDemo.java" "db/ReadOnlyAccountDemo.java"

echo "附录已追加。"
