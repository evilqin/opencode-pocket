#!/bin/bash
# 安装容器里需要的基础工具（git 是 opencode 快照/回滚的依赖）。
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

# 上一次被中断（取消/断电/超时）时的自救：dpkg 会留下“未配置”状态，
# 不修就会一直卡在 "E: dpkg was interrupted"。
mkdir -p /var/lib/dpkg/updates /var/lib/dpkg/info /var/cache/apt/archives/partial
if [ -f /var/lib/dpkg/updates/0000 ] || dpkg --audit 2>/dev/null | grep -q .; then
    echo "检测到 dpkg 处于中断状态，先修复…"
    dpkg --configure -a || true
fi
apt-get -o APT::Sandbox::User=root -o DPkg::Lock::Timeout=60 -y -f install || true

# 索引缺失就先自己刷新一次：上一步的 apt-get update 可能没写成（容器被清理过、
# 或用户只重跑了「安装基础工具」这一步），否则后面会报 "Unable to locate package"。
if ! ls /var/lib/apt/lists/*_Packages* >/dev/null 2>&1; then
    echo "apt 索引缺失，先刷新一次"
    apt-get update
fi

# --force-confdef/--force-confold：升级 conffile 时不弹交互（容器里没有 tty，弹了就挂死）
DPKG_OPTS=(-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)

apt-get -y --no-install-recommends "${DPKG_OPTS[@]}" install \
    ca-certificates \
    curl \
    git \
    xz-utils \
    unzip \
    tar \
    procps \
    less \
    file \
    ripgrep \
    tzdata

update-ca-certificates >/dev/null 2>&1 || true

# 让 git 能正常工作
git config --system --add safe.directory '*' >/dev/null 2>&1 || true

echo "POCKET_TOOLS_READY"
git --version
curl --version | head -n 1
rg --version | head -n 1
