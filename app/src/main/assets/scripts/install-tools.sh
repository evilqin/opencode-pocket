#!/bin/bash
# 安装容器里需要的基础工具（git 是 opencode 快照/回滚的依赖）。
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

# 上一次被中断（取消/断电/超时）时的自救：dpkg 会留下“未配置”状态，
# 不修就会一直卡在 "E: dpkg was interrupted"。
mkdir -p /var/lib/dpkg/updates /var/lib/dpkg/info /var/cache/apt/archives/partial

# https 的 apt 源必须有 CA 证书，否则会以 "No system certificates available" 全量失败，
# 而那时 apt-get update 仍然返回 0（只有 W: 警告），后面 install 才报 "Unable to locate package"。
# 这里把它变成一句能看懂的话，并告诉用户该重跑哪一步。
if grep -q '^deb https://' /etc/apt/sources.list 2>/dev/null && [ ! -s /etc/ssl/certs/ca-certificates.crt ]; then
    echo "缺少 CA 证书（/etc/ssl/certs/ca-certificates.crt 为空）：https 的 apt 源无法校验。" >&2
    echo "请在 App 里重跑「安装 Ubuntu 环境」这一步（它会从系统证书库注入 CA），再重试本步骤。" >&2
    exit 1
fi

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
