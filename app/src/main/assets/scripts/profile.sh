# OpenCode Pocket —— 容器内 shell 环境（登录时加载）
# 这个文件由 App 在安装时写到 /etc/profile.d/90-pocket.sh

export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export TERM=${TERM:-xterm-256color}
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

# 手机终端上更好用的提示符
PS1='\[\e[38;5;44m\]pocket\[\e[0m\]:\[\e[38;5;111m\]\w\[\e[0m\]\$ '

alias ll='ls -alF --color=auto'
alias la='ls -Alh --color=auto'
alias lt='ls -Alht --color=auto'
alias ls='ls --color=auto'
alias grep='grep --color=auto'
alias oc='opencode'

# git：容器里所有文件都归 fake root，必须放开 safe.directory；手机文件系统也没有可用的 fileMode
git config --global --add safe.directory '*' >/dev/null 2>&1 || true
git config --global init.defaultBranch main >/dev/null 2>&1 || true
git config --global core.fileMode false >/dev/null 2>&1 || true
git config --global core.autocrlf input >/dev/null 2>&1 || true

# 首次进入时的欢迎信息（只在交互式登录 shell 里出现）
if [ -n "$PS1" ] && [ -z "$POCKET_WELCOMED" ]; then
    export POCKET_WELCOMED=1
    if [ -f /root/.pocket-banner ]; then
        cat /root/.pocket-banner
    fi
fi
