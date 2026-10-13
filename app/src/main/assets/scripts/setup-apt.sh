#!/bin/bash
# 配置 apt 源（由 App 注入镜像地址与发行版代号），并刷新索引。
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

APT_MIRROR="@APT_MIRROR@"
DISTRO="@DISTRO@"

mkdir -p /etc/apt/apt.conf.d /etc/apt/sources.list.d /var/lib/apt/lists/partial /var/cache/apt/archives/partial

# 容器里没有 init 系统：阻止维护脚本尝试启停服务（否则个别 postinst 会失败）
cat > /usr/sbin/policy-rc.d <<'EOF'
#!/bin/sh
exit 101
EOF
chmod 755 /usr/sbin/policy-rc.d

# Ubuntu 24.04 的 base 镜像用 deb822 格式，这里统一改成经典 sources.list，避免两份源打架
rm -f /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list.d/*.sources 2>/dev/null || true
KEYRING=/usr/share/keyrings/ubuntu-archive-keyring.gpg
if [ -s "$KEYRING" ]; then
    SIGNED="[signed-by=$KEYRING]"
else
    # 极少数精简镜像没有 keyring：退回不校验签名（后续装好 ca-certificates 后由 update-ca-certificates 兜底）
    SIGNED="[trusted=yes]"
fi

cat > /etc/apt/sources.list <<EOF
deb $SIGNED $APT_MIRROR $DISTRO main restricted universe multiverse
deb $SIGNED $APT_MIRROR $DISTRO-updates main restricted universe multiverse
deb $SIGNED $APT_MIRROR $DISTRO-security main restricted universe multiverse
EOF

# 容器里没有 systemd，apt 的 helper 一律关掉
cat > /etc/apt/apt.conf.d/00-pocket <<'EOF'
APT::Sandbox::User "root";
APT::Install-Recommends "0";
APT::Install-Suggests "0";
Acquire::Retries "2";
Acquire::http::Pipeline-Depth "0";
Acquire::PDiffs "false";
DPkg::Lock::Timeout "60";
EOF

apt-get update
echo "POCKET_APT_READY"
