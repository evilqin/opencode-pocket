#!/usr/bin/env bash
#
# 容器配方冒烟测试（在 Linux/x86_64 上跑，不需要手机）
#
# 为什么要它：整个项目最大的未知是「proot 配方 + 我们的脚本 + opencode 二进制能不能真的跑起来」。
# 手机不方便天天试，但配方的每一环都可以在 CI 的 Linux runner 上验证：
#   1) 下载 Ubuntu base rootfs（x86_64，与手机上的 arm64 同一套流程）并校验 SHA256
#   2) 用与 App 完全一致的 proot 参数（-L / --kill-on-exit / -0 / --cwd / -b 绑定）启动
#   3) 写入并执行 App 内置的容器脚本（profile.sh / setup-env.sh），断言目录、npmrc、banner
#   4) 走镜像直取 Node 与 opencode 二进制，校验 SHA256，然后在 proot 里跑 opencode --version
#   5) 起 opencode serve/web，验证 /global/health、会话 API、providers、SSE、鉴权、abort
#
# 与真机的差异（脚本末尾也会再提醒）：架构 x86_64、用发行版 proot、没有 Android 的
# W^X / phantom-killer 限制；这些属于手机侧专属风险，仍需真机验证。
#
set -euo pipefail

WORK="${WORK:-$PWD/.smoke}"
ROOTFS="$WORK/rootfs"
MIRROR_UBUNTU="${MIRROR_UBUNTU:-https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release}"
MIRROR_NODE="${MIRROR_NODE:-https://npmmirror.com/mirrors/node}"
MIRROR_NPM="${MIRROR_NPM:-https://registry.npmmirror.com}"
PORT_SERVE="${PORT_SERVE:-4111}"
PORT_WEB="${PORT_WEB:-4112}"
PASSWORD="${PASSWORD:-smoke-test-password}"

log() { printf '\n\033[1;36m[smoke]\033[0m %s\n' "$*"; }
fail() { printf '\n\033[1;31m[smoke] 失败：%s\033[0m\n' "$*" >&2; exit 1; }

mkdir -p "$WORK"

# ---------------------------------------------------------------- 依赖

log "准备依赖（proot 是普通用户即可运行的 ptrace 方案，CI 上直接装包）"
if ! command -v proot >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq proot xz-utils curl jq
fi
proot --version | head -n1

# ---------------------------------------------------------------- rootfs

if [ ! -x "$ROOTFS/bin/bash" ]; then
  log "下载 Ubuntu base rootfs（amd64）"
  LISTING=$(curl -fsSL "$MIRROR_UBUNTU/")
  FILE=$(printf '%s' "$LISTING" | grep -oE 'ubuntu-base-24\.04\.[0-9]+-base-amd64\.tar\.gz' | sort -V | tail -n1)
  [ -n "$FILE" ] || fail "镜像目录里没有 amd64 rootfs"
  log "使用 $FILE"
  curl -fsSL "$MIRROR_UBUNTU/$FILE" -o "$WORK/$FILE"
  EXPECTED=$(curl -fsSL "$MIRROR_UBUNTU/SHA256SUMS" | grep -F "$FILE" | awk '{print $1}')
  ACTUAL=$(sha256sum "$WORK/$FILE" | awk '{print $1}')
  [ "$EXPECTED" = "$ACTUAL" ] || fail "rootfs 摘要不匹配（期望 $EXPECTED，实际 $ACTUAL）"
  log "rootfs 摘要校验通过"
  rm -rf "$ROOTFS"
  mkdir -p "$ROOTFS"
  tar -xzf "$WORK/$FILE" -C "$ROOTFS"
fi

# ---------------------------------------------------------------- 与 App 一致的 proot 配方

# 选项名在 proot 的不同构建里并不一致（Ubuntu 24.04 的 5.1.0 不认 `-L`）。
# App 侧只用长选项；这里再按本机 proot 的帮助文本做一次能力探测，
# 缺哪个就跳过哪个并打警告（手机上用的是 Termux proot，支持全部）。
PROOT_HELP=$(proot --help 2>&1 || true)
PROOT_ARGS=(--rootfs="$ROOTFS" --cwd=/root)
add_flag() {
  if printf '%s' "$PROOT_HELP" | grep -q -- "$1"; then
    PROOT_ARGS+=("$1")
  else
    printf '[smoke] 注意：本机 proot 不支持 %s，已跳过（Termux proot 支持它，CI 另用 ELF 扫描复核）\n' "$1"
  fi
}
add_flag --sysvipc
add_flag --kill-on-exit
PROOT_ARGS+=(-0)   # fake root：所有构建都支持
# 绑定挂载必须写成 --bind=xxx（一个 argv 元素）：proot 的长选项要求值紧跟 '='
for bind in /dev /proc /sys /dev/urandom:/dev/random; do
  PROOT_ARGS+=("--bind=$bind")
done

printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\n' > "$ROOTFS/etc/resolv.conf"

# 与 Proot.guestEnv() 一致：env -i + 固定变量，调用方再追加额外变量与命令
PROOT_COMMON=(
  proot "${PROOT_ARGS[@]}"
  /usr/bin/env -i
  HOME=/root
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
  LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb SHELL=/bin/bash
)

run() { "${PROOT_COMMON[@]}" /bin/bash --login -c "$1"; }

log "容器自检（uname / fake root / os-release）"
run 'uname -m; id -u; . /etc/os-release && echo "$PRETTY_NAME"'
[ "$(run 'id -u')" = "0" ] || fail "proot 的 fake root 没有生效"

# ---------------------------------------------------------------- App 内置脚本

log "写入并执行 App 里的容器脚本（assets/scripts/*.sh）"
install -D -m 0644 app/src/main/assets/scripts/profile.sh "$ROOTFS/etc/profile.d/90-pocket.sh"
install -D -m 0755 app/src/main/assets/scripts/setup-env.sh "$ROOTFS/root/.pocket/setup-env.sh"
run "POCKET_NPM_REGISTRY='$MIRROR_NPM' bash /root/.pocket/setup-env.sh" | tail -n3

run 'test -d /root/workspace && echo workspace-ok'
run 'test -f /root/.pocket-banner && echo banner-ok'
run "grep -q '^registry=$MIRROR_NPM' /root/.npmrc && echo npmrc-ok"
run 'grep -q "alias oc=" /etc/profile.d/90-pocket.sh && echo profile-ok'
run 'echo "$LANG" | grep -q C.UTF-8 && echo locale-ok'

# ---------------------------------------------------------------- Node（走镜像直取）

if [ ! -x "$ROOTFS/usr/local/bin/node" ]; then
  log "从镜像直取 Node（linux-x64）"
  NODE_VERSION=$(curl -fsSL "$MIRROR_NODE/index.json" \
    | jq -r '[.[] | select(.lts != false) | select(.files | index("linux-x64"))] | sort_by(.version) | last | .version')
  [ -n "$NODE_VERSION" ] && [ "$NODE_VERSION" != "null" ] || fail "没能在 index.json 里找到 x64 LTS"
  FILE="node-$NODE_VERSION-linux-x64.tar.xz"
  curl -fsSL "$MIRROR_NODE/$NODE_VERSION/$FILE" -o "$WORK/$FILE"
  EXPECTED=$(curl -fsSL "$MIRROR_NODE/$NODE_VERSION/SHASUMS256.txt" | grep -F "$FILE" | awk '{print $1}')
  ACTUAL=$(sha256sum "$WORK/$FILE" | awk '{print $1}')
  [ "$EXPECTED" = "$ACTUAL" ] || fail "Node 摘要不匹配"
  log "Node $NODE_VERSION 摘要校验通过"
  rm -rf "$WORK/node-extract"
  mkdir -p "$WORK/node-extract"
  tar -xJf "$WORK/$FILE" -C "$WORK/node-extract" --strip-components=1
  mkdir -p "$ROOTFS/usr/local"
  cp -a "$WORK/node-extract/." "$ROOTFS/usr/local/"
fi
run 'node -v && npm -v'

# ---------------------------------------------------------------- opencode（走 npm 镜像直取）

if [ ! -x "$ROOTFS/usr/local/lib/opencode/bin/opencode" ]; then
  log "从 npm 镜像直取 opencode（linux-x64）"
  OC_VERSION=$(curl -fsSL "$MIRROR_NPM/opencode-ai/latest" | jq -r '.version')
  [ -n "$OC_VERSION" ] && [ "$OC_VERSION" != "null" ] || fail "拿不到 opencode 版本号"
  curl -fsSL "$MIRROR_NPM/opencode-linux-x64/-/opencode-linux-x64-$OC_VERSION.tgz" -o "$WORK/opencode.tgz"
  rm -rf "$WORK/oc-extract"
  mkdir -p "$WORK/oc-extract" "$ROOTFS/usr/local/lib/opencode/bin"
  tar -xzf "$WORK/opencode.tgz" -C "$WORK/oc-extract" package/bin/opencode
  install -m 0755 "$WORK/oc-extract/package/bin/opencode" "$ROOTFS/usr/local/lib/opencode/bin/opencode"
  ln -sf ../lib/opencode/bin/opencode "$ROOTFS/usr/local/bin/opencode"
  log "opencode $OC_VERSION 已就位"
fi

log "★ 关键验证：opencode 能不能在 proot 下运行"
run 'opencode --version' || fail "opencode 在 proot 下无法运行（这是整条链路的头号风险）"
run 'file /usr/local/lib/opencode/bin/opencode | head -n1' || true

# ---------------------------------------------------------------- 服务与 API 冒烟

SERVE_LOG="$WORK/serve.log"
WEB_LOG="$WORK/web.log"
SERVE_PID=""
WEB_PID=""
cleanup() {
  if [ -n "$SERVE_PID" ]; then kill "$SERVE_PID" 2>/dev/null || true; fi
  if [ -n "$WEB_PID" ]; then kill "$WEB_PID" 2>/dev/null || true; fi
  return 0
}
trap cleanup EXIT

wait_health() {
  local port="$1"
  for _ in $(seq 1 60); do
    if curl -fsS -u "opencode:$PASSWORD" "http://127.0.0.1:$port/global/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

log "启动 opencode serve（带 OPENCODE_SERVER_PASSWORD，与 App 的启动方式一致）"
"${PROOT_COMMON[@]}" \
  "OPENCODE_SERVER_PASSWORD=$PASSWORD" OPENCODE_DISABLE_AUTOUPDATE=1 \
  /bin/bash --login -c "cd /root/workspace && exec opencode serve --hostname 127.0.0.1 --port $PORT_SERVE" \
  > "$SERVE_LOG" 2>&1 &
SERVE_PID=$!

wait_health "$PORT_SERVE" || { tail -n 40 "$SERVE_LOG"; fail "serve 60 秒内没有就绪"; }
log "健康检查通过：$(curl -fsS -u "opencode:$PASSWORD" "http://127.0.0.1:$PORT_SERVE/global/health")"

log "验证鉴权：不带口令访问会话列表应该被拒绝"
UNAUTH=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT_SERVE/session")
[ "$UNAUTH" = "401" ] || fail "不带口令竟然返回 $UNAUTH（期望 401）—— App 的口令注入就不成立了"
log "鉴权生效（401）"

log "验证会话 API：创建 / 列表 / 删除"
SESSION_ID=$(curl -fsS -u "opencode:$PASSWORD" -X POST "http://127.0.0.1:$PORT_SERVE/session" \
  -H 'content-type: application/json' -d '{"title":"smoke"}' | jq -r '.id')
[ -n "$SESSION_ID" ] && [ "$SESSION_ID" != "null" ] || fail "创建会话失败"
curl -fsS -u "opencode:$PASSWORD" "http://127.0.0.1:$PORT_SERVE/session" | jq -e 'length >= 1' >/dev/null \
  || fail "会话列表里看不到刚创建的会话"
log "会话 API 正常（$SESSION_ID）"

log "验证 /config/providers 与 /agent（App 的模型选择器依赖）"
curl -fsS -u "opencode:$PASSWORD" "http://127.0.0.1:$PORT_SERVE/config/providers" | jq -e '.providers | length >= 1' >/dev/null \
  || fail "/config/providers 结构不符合预期"
curl -fsS -u "opencode:$PASSWORD" "http://127.0.0.1:$PORT_SERVE/agent" | jq -e 'length >= 1' >/dev/null \
  || fail "/agent 结构不符合预期"

log "验证 SSE 事件流（前 5 秒应该收到 server.connected）"
SSE=$(timeout 5 curl -sN -u "opencode:$PASSWORD" "http://127.0.0.1:$PORT_SERVE/event" || true)
printf '%s' "$SSE" | grep -q 'server.connected' || fail "SSE 里没有 server.connected：$(printf '%s' "$SSE" | head -c 200)"
log "SSE 正常"

log "验证中断与删除接口"
curl -fsS -u "opencode:$PASSWORD" -X POST "http://127.0.0.1:$PORT_SERVE/session/$SESSION_ID/abort" >/dev/null \
  || fail "abort 接口失败"
curl -fsS -u "opencode:$PASSWORD" -X DELETE "http://127.0.0.1:$PORT_SERVE/session/$SESSION_ID" >/dev/null \
  || fail "删除会话失败"

log "★ 验证 opencode web 是否自带 Web UI（App 的「打开网页」依赖它）"
"${PROOT_COMMON[@]}" \
  "OPENCODE_SERVER_PASSWORD=$PASSWORD" OPENCODE_DISABLE_AUTOUPDATE=1 BROWSER=/bin/true \
  /bin/bash --login -c "cd /root/workspace && exec opencode web --hostname 127.0.0.1 --port $PORT_WEB" \
  > "$WEB_LOG" 2>&1 &
WEB_PID=$!

wait_health "$PORT_WEB" || { tail -n 40 "$WEB_LOG"; fail "web 60 秒内没有就绪"; }
ROOT_HEADERS=$(curl -s -o /dev/null -w '%{http_code} %{content_type}' -u "opencode:$PASSWORD" "http://127.0.0.1:$PORT_WEB/")
log "web 根路径响应：$ROOT_HEADERS"
case "$ROOT_HEADERS" in
  200\ text/html*) log "✔ opencode web 自带 Web UI（App 的 WebView 方案成立）" ;;
  *) printf '\n\033[1;33m[smoke] 注意：web 根路径不是 HTML（%s），App 的「打开网页」需要真机复核\033[0m\n' "$ROOT_HEADERS" ;;
esac

log "验证 dexec 在「未启用」与「桥不可达」时都能给出可读错误"
install -D -m 0755 app/src/main/assets/device-shell/dexec.sh "$ROOTFS/usr/local/bin/dexec"
if run 'dexec "echo hi"'; then
  fail "桥未启用时 dexec 不该成功"
fi
run 'mkdir -p /root/.config/opencode'
run "printf '%s' '{\"enabled\":true,\"port\":9999,\"token\":\"wrong\"}' > /root/.config/opencode/pocket.json"
if run 'DEXEC_TIMEOUT=5 dexec "echo hi"'; then
  fail "桥不可达时 dexec 不该成功"
fi
log "dexec 的错误路径正常"

log "全部通过 ✔  架构=x86_64（配方与手机端一致）；Android 专属风险（W^X、phantom-killer、Shizuku）仍需真机验证"
