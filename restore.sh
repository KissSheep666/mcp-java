#!/usr/bin/env bash
# 从 .reference 恢复完整实现到 src/。
#
# 用法：
#   bash restore.sh                    # 全部恢复
#   bash restore.sh db/SqlGuard.java   # 只恢复一个文件
#
# ⚠️ 会覆盖你在 src/ 里写的内容。恢复前会先备份到 .mine/。
set -euo pipefail

REF=".reference/java/com/example/mcp"
DST="src/main/java/com/example/mcp"
BACKUP=".mine"

if [ ! -d "$REF" ]; then
  echo "找不到 .reference/，无法恢复。" >&2
  exit 1
fi

restore_one() {
  local rel="$1"
  local src="$REF/$rel"
  local dst="$DST/$rel"

  if [ ! -f "$src" ]; then
    echo "  ✗ 参考文件不存在：$rel" >&2
    return 1
  fi

  # 如果目标文件有内容，先备份
  if [ -s "$dst" ]; then
    mkdir -p "$(dirname "$BACKUP/$rel")"
    cp "$dst" "$BACKUP/$rel"
    printf "  %-46s (你的版本已备份到 .mine/)\n" "$rel"
  else
    printf "  %-46s\n" "$rel"
  fi

  mkdir -p "$(dirname "$dst")"
  cp "$src" "$dst"
}

if [ $# -ge 1 ]; then
  echo "恢复单个文件："
  restore_one "$1"
else
  echo "恢复全部文件："
  (cd "$REF" && find . -name "*.java" | sed 's|^\./||' | sort) | while read -r rel; do
    restore_one "$rel"
  done
fi

echo
echo "完成。你的原文件（如果有内容）都在 $BACKUP/ 下，恢复回来用："
echo "  cp -r $BACKUP/* $DST/"
