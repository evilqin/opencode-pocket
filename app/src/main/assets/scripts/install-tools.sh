#!/bin/bash
# 安装容器里需要的基础工具（git 是 opencode 快照/回滚的依赖）。
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get -y --no-install-recommends install \
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
