#!/bin/bash
# dexec —— 在手机本体（Android）上执行命令，而不是在容器里。
# 由 OpenCode Pocket 注入：需要 App 里开启「设备 Shell 桥」并完成 Shizuku 授权。
#
# 用法：
#   dexec 'getprop ro.product.model'
#   DEXEC_TIMEOUT=300 dexec 'pm list packages'      # 覆盖默认 60s 超时
#
# 退出码：0 = 命令成功；22 = 远端命令以非 0 退出（配合 --fail-with-body）
#         28 = 等响应超时；2 = 未启用/配置不完整；64 = 用法错误
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
    echo "     DEXEC_TIMEOUT=300 dexec 'pm list packages'" >&2
    exit 64
fi

TIMEOUT=${DEXEC_TIMEOUT:-60}
case "$TIMEOUT" in
    ''|*[!0-9]*) TIMEOUT=60 ;;
esac

# 令牌不经过命令行参数：容器里任何进程都能看 /proc 下的 argv，
# 改用 0600 的临时头文件传给 curl（-H @文件，curl 7.55+）
HDRFILE="$(mktemp /tmp/dexec-hdr.XXXXXX 2>/dev/null)" || {
    echo "dexec: 无法创建临时文件（/tmp 不可写）" >&2
    exit 2
}
chmod 600 "$HDRFILE" 2>/dev/null
trap 'rm -f "$HDRFILE"' EXIT
printf 'Authorization: Bearer %s\n' "$token" > "$HDRFILE"

curl -sS --max-time "$((TIMEOUT + 15))" --fail-with-body \
    -H @"$HDRFILE" \
    -H "Content-Type: text/plain; charset=utf-8" \
    -H "X-Dexec-Timeout-Ms: $((TIMEOUT * 1000))" \
    -H "Expect:" \
    --data-binary "$*" \
    "http://127.0.0.1:${port}/exec"
status=$?

if [ $status -eq 22 ]; then
    # HTTP 4xx/5xx：body 已经打印过了，这里只补充一句说明
    echo "dexec: 远端返回错误（见上方输出；401 = 令牌失效，重新开启设备 Shell 即可）" >&2
elif [ $status -eq 28 ]; then
    echo "dexec: 等待超时（${TIMEOUT}s）" >&2
elif [ $status -eq 124 ]; then
    echo "dexec: 命令在手机侧超时（${TIMEOUT}s），已被终止" >&2
fi
exit $status
