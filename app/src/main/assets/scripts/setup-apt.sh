#!/bin/bash
# 配置 apt 源（由 App 注入镜像地址与发行版代号），并刷新索引。
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

APT_MIRROR="@APT_MIRROR@"
DISTRO="@DISTRO@"

# 发行版代号会被拼进 sources.list：只接受纯小写字母，防止被注入
case "$DISTRO" in
    *[!a-z]*) echo "发行版代号不合法：$DISTRO" >&2; exit 1 ;;
esac
case "$APT_MIRROR" in
    https://*) ;;
    *) echo "apt 源必须是 https：$APT_MIRROR" >&2; exit 1 ;;
esac

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
if [ ! -s "$KEYRING" ]; then
    # 没有 keyring 就无法校验包签名：明确失败，绝不 [trusted=yes] 放行
    echo "缺少 $KEYRING，无法校验软件包签名；请换一个完整镜像重装 rootfs" >&2
    exit 1
fi
SIGNED="[signed-by=$KEYRING]"

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
