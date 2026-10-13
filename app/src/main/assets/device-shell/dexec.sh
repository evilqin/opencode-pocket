#!/bin/bash
# dexec —— 在手机本体（Android）上执行命令，而不是在容器里。
# 由 OpenCode Pocket 注入：需要 App 里开启「设备 Shell 桥」并完成 Shizuku 授权。
set -uo pipefail

CONFIG=/root/.config/opencode/pocket.json
if [ ! -f "$CONFIG" ]; then
    echo "dexec: 没有找到 $CONFIG（在 App 的 设置 → 设备 Shell 里开启）" >&2
    exit 2
fi

enabled=$(sed -n 's/.*"enabled"[[:space:]]*:[[:space:]]*\(true\|false\).*/\1/p' "$CONFIG" | head -n1)
port=$(sed -n 's/.*"port"[[:space:]]*:[[:space:]]*\([0-9]\{2,5\}\).*/\1/p' "$CONFIG" | head -n1)
token=$(sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$CONFIG" | head -n1)

if [ "$enabled" != "true" ]; then
    echo "dexec: 设备 Shell 桥未启用（App → 设置 → 设备 Shell）" >&2
    exit 2
fi
if [ -z "${port:-}" ] || [ -z "${token:-}" ]; then
    echo "dexec: $CONFIG 内容不完整（port/token）" >&2
    exit 2
fi
if [ $# -eq 0 ]; then
    echo "用法: dexec <命令>        # 在手机本机上执行（shell/root 身份）" >&2
    echo "例:   dexec 'getprop ro.product.model'" >&2
    exit 64
fi

TIMEOUT=${DEXEC_TIMEOUT:-60}
curl -sS --max-time "$((TIMEOUT + 5))" \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: text/plain" \
    --data-binary "$*" \
    "http://127.0.0.1:${port}/exec"
