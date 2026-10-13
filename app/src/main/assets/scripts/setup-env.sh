#!/bin/bash
# 收尾：目录、权限、欢迎信息、npm registry。
set -eu

mkdir -p /root/workspace
mkdir -p /root/.config/opencode
mkdir -p /root/.local/share/opencode
mkdir -p /root/.cache
mkdir -p /tmp /var/tmp /run/lock
mkdir -p /dev/shm
mkdir -p /var/cache/apt/archives/partial /var/lib/apt/lists/partial
chmod 1777 /tmp /var/tmp /dev/shm
chmod 1777 /run/lock 2>/dev/null || true

# npm registry（写文件，不用跑 npm）
if [ -n "${POCKET_NPM_REGISTRY:-}" ]; then
    printf 'registry=%s\n' "$POCKET_NPM_REGISTRY" > /root/.npmrc
fi

cat > /root/.pocket-banner <<'EOF'

  ╭──────────────────────────────────────────────╮
  │  OpenCode Pocket · 容器内的 Ubuntu           │
  │                                              │
  │  ~/workspace   手机上的项目目录（已挂载）    │
  │  opencode      启动 opencode（服务在手机上） │
  │  dexec         在手机本体上执行命令（可选）  │
  │  git/node/npm  常用工具都可用                │
  ╰──────────────────────────────────────────────╯

EOF

cat > /root/README.md <<'EOF'
# 欢迎使用 OpenCode Pocket

这里是容器内的 Ubuntu（aarch64），你手机上的项目通过共享目录出现在 `/root/workspace`。

常用命令：

```bash
cd /root/workspace     # 进入共享工作区
opencode --version     # 查看版本
opencode web --port 4096   # 手动起一个网页版服务
dexec 'getprop ro.product.model'   # 在手机本机上执行命令（需在 App 里开启设备 Shell）
```

- 想改模型/密钥：回到 App 的「设置 → 模型与密钥」，保存后重启服务即可。
- 工作区在手机上的真实路径，见 App「设置 → 工作区」。
- `dexec` 走的是手机上的 Shizuku，只能操作 Android 那一侧（容器外的路径不存在），
  两边传文件请用共享工作区。
EOF

echo "POCKET_ENV_READY"
