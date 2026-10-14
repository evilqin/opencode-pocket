---
name: device-shell
description: 在手机本体（Android 系统，而非容器）上执行 shell 命令。当需要查看设备信息、剪贴板、通知、屏幕状态，或访问 /sdcard 之外的系统路径时使用；容器里没有这些能力。
metadata:
  opencode/slash: "false"
---

## 我能做什么

`dexec` 是通过 Shizuku 授权、由 OpenCode Pocket 提供的命令，它在**手机本身**上以 shell（或 root）身份执行命令，
输出直接返回。适合这些场景：

- 设备信息：`dexec 'getprop ro.product.model'`、`dexec 'dumpsys battery'`
- 剪贴板：`dexec 'cmd clipboard get'`（部分 ROM 支持）
- 屏幕/窗口：`dexec 'dumpsys window | head -40'`
- 存储与包：`dexec 'pm list packages | grep -i 星'`、`dexec 'ls /sdcard/Download | head'`
- 网络：`dexec 'ip addr show wlan0'`
- 在系统里放/取文件：`dexec 'cp /sdcard/Download/a.txt /data/local/tmp/'`

## 注意事项

- 它**不是**容器的 sudo：`dexec` 在 Android 侧运行，看不到容器里的路径（`/root/...` 不存在）。
  需要在两边传文件时，用共享工作区 `/root/workspace`（就是手机上的项目目录）。
- 只能执行**只读或辅助性**操作，不要用它改系统分区、卸载系统应用；
  真要做破坏性操作前，先把命令给用户看，得到确认再执行。
- **危险命令会被防护拦下**：`rm -rf /`、`pm uninstall`、`dd of=/dev/block/...`、`settings put`（幻影进程那条除外）
  等会直接返回 403 并说明原因。被拦时不要试着绕（换写法、Base64、写脚本再执行都属于绕过），
  而是把命令展示给用户，让他在 App 里决定。
- 输出上限 256 KiB，超时会终止：`DEXEC_TIMEOUT=秒 前缀`，例如 `DEXEC_TIMEOUT=120 dexec '...'`。
  **上限是 600 秒**（Shizuku 用户服务侧与 App 侧都卡在 10 分钟）。
- 退出码：0 成功；22 = 远端命令以非 0 退出（含被防护拦下的 403）；28/124 = 超时；2 = 未启用/配置不完整。
- 每次调用（含被拦）都会写进 App 的审计日志（`files/linux/logs/device-shell.log`），
  用户可以在「设置 → 设备 Shell → 最近执行记录」里看到。
- 如果提示「设备 Shell 桥未启用」，让用户到 App 的「设置 → 设备 Shell」里开启并授权 Shizuku。
