# OpenCode 口袋（OpenCode Pocket）

把官方 [opencode](https://github.com/anomalyco/opencode) 装进一台「口袋里的 Ubuntu」——原生 Android 应用，
内置 proot 运行时，无需 root、无需 Termux、无需电脑，手机上点几下就能用。

> 参考了 [DSHA（DeepSeek Harness 安卓启动器）](https://github.com/qiannianhuanxiang/DSHA) 的容器方案：
> Termux 官方 proot 二进制放进 APK、rootfs 放应用私有目录、多源测速、分步安装。
> 本项目的目标是同样的“一体化”体验，但服务对象换成 opencode。

---

## 它是什么

- **一个启动器**：内置 `proot`（Termux 官方 aarch64 构建）+ 一键安装的 Ubuntu 24.04 rootfs。
- **一个原生客户端**：对话页直连容器里的 opencode 服务（REST + SSE），流式输出、工具调用卡片、
  权限确认；同时也能打开官方 Web UI。
- **一个终端**：在容器里跑任意命令，看安装日志与运行日志。
- **完全离线可控**：所有下载都走**你选的镜像**，密钥只写入 App 私有目录，不上传任何地方。

---

## 功能一览

| 模块 | 说明 |
| --- | --- |
| 一键安装 | 4 步流水线：运行时就绪 → Ubuntu rootfs → 基础工具（apt）→ OpenCode 本体；每步可单独重试、可续传 |
| 多源测速 | Ubuntu / apt / Node / npm 四类镜像，用**真实文件**做 Range 测速（延迟 + 带宽），自动选最快 |
| 服务管理 | 前台服务常驻，通知栏显示状态与「停止」动作；健康检查轮询 `/global/health` |
| 原生对话 | 会话列表、流式回复、思考过程折叠、工具调用卡片、权限请求（允许一次/始终允许/拒绝）、模型与代理切换 |
| Web UI | 内嵌 WebView 打开 `opencode web` 的官方界面，可跳外部浏览器 |
| 终端 | 类 shell 的命令执行（cwd 保持），快捷命令；安装日志 / 运行日志各一份，可复制可分享 |
| 设置 | 12 个常用 provider 的 API Key、默认模型、端口、权限策略、低内存模式、工作区、主题、维护工具 |
| 工作区 | 手机上的真实目录（默认 `/sdcard/OpenCode`）挂进容器 `/root/workspace`，容器里直接读写你的项目 |
| 一键体检 | 12 项只读检查（架构/运行时/rootfs/git/opencode/域名解析/空间/端口/密钥/权限…），结果可一键复制 |
| 设备 Shell 桥 | 可选：通过 **Shizuku** 让容器里的 agent 用 `dexec` 在手机本体上执行命令（读设备信息、剪贴板、系统目录） |
| 安全默认 | `share: disabled`、`autoupdate: false`、密钥写在应用私有目录、不采集任何数据 |

---

## 安装

### 方式一：直接装 APK

1. 到 [Releases](../../releases) 下载 `app-release.apk`（或自己构建，见下）。
2. 手机需要 **ARM64**，Android **11（API 30）及以上**。
3. 安装后打开 → 「开始安装」→ 选镜像 → 等 5 分钟左右。

> 仓库里的 `dist/` 放了三份构建产物（当前 1.1.0）：
> `*-arm64.apk`（标准包，28.1 MB，未混淆，最稳）、
> `*-arm64-slim.apk`（R8 精简包，**4.1 MB**，体积小但需要真机回归一遍）、
> `*-debug.apk`（调试包，36.5 MB，日志最全）。

### 方式二：自己构建

```bash
git clone <this-repo> && cd opencode-pocket

# 1) 拉取内置的 proot 运行时（Termux 官方包，约 400KB）
node tools/fetch-boot-runtime.mjs

# 2) 单元测试 + 构建
./gradlew :app:testDebugUnitTest    # 50 个纯逻辑用例（解析 / 配置 / 版本 / 上游文件 / Markdown / 格式化）
./gradlew :app:assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # 默认用 debug 签名，可直接安装

# 可选：R8 精简包（4 MB 左右；会重写代码，务必真机回归）
./gradlew :app:assembleRelease "-Ppocket.minify=true"
```

**发布新版本**：改 `app/build.gradle.kts` 里的 `versionCode` / `versionName`，然后推一个同名标签即可自动构建并发布 Release（CI 会先校验 tag 与 versionName 一致）：

```bash
git tag v1.1.2 && git push origin v1.1.2
```

环境要求：JDK 21+、Android SDK（platform 37 + build-tools 36.0.0）、Node 20+（只用来跑取件脚本）。
也可以直接用仓库里的 GitHub Actions 工作流：推送到 `main` 或手动 `Run workflow`，产物在 Artifacts 里。

构建完成后可以跑一次打包自检（校验内置 ELF 没被改写、ABI 只有 arm64、容器脚本齐全）：

```bash
node tools/verify-apk.mjs app/build/outputs/apk/release/app-release.apk
# [verify-apk] 通过：4 个原生库字节一致，4 个脚本齐全，ABI=arm64-v8a
```

> 国内网络提示：Gradle 官方分发地址可能连不上，把 `gradle/wrapper/gradle-wrapper.properties`
> 里的 `distributionUrl` 换成 `https://mirrors.cloud.tencent.com/gradle/gradle-9.5.0-bin.zip`
> 即可（记得同步 `distributionSha256Sum`，或直接删掉这一行）。

---

## 首次使用

1. **安装环境**（安装页）
   - 建议先点「全线测速」挑最快的镜像；
   - 点「一键安装」，保持 App 在前台；
   - 下载 rootfs（约 30 MB）与 OpenCode 本体（约 90 MB）都支持断点续传，中断了再点一次即可。
2. **配置模型**（设置 → 模型与密钥）
   - 填任意一个 provider 的 API Key（DeepSeek / Anthropic / OpenAI / OpenRouter / 通义 / 智谱 / 硅基流动…）；
   - 想指定默认模型就填 `provider/model`，例如 `deepseek/deepseek-chat`；
   - 点「保存并重启服务」生效。
3. **开聊**（对话页）或 **开网页**（首页 → 打开网页）。
4. **工作区**：默认把 `/sdcard/OpenCode` 挂进容器。第一次用建议在首页点「去授权」给「所有文件访问权限」，
   或者直接改成内部工作区（设置 → 工作区）。

---

## 技术方案

```
┌─────────────────────────── Android App（Kotlin + Compose） ───────────────────────────┐
│  首页 / 对话 / 终端 / 设置            原生客户端：OkHttp + SSE ←→ 127.0.0.1:4096      │
│  ServerService（前台服务，通知栏状态 + 停止动作，Partial WakeLock）                    │
│  Installer（分步流水线）  Repos（测速/发现版本）  Downloader（续传/多源）  TarExtract    │
└───────────────┬───────────────────────────────────────────────────────────────────────┘
                │ ProcessBuilder（LD_LIBRARY_PATH / PROOT_LOADER / PROOT_TMP_DIR）
        ┌───────▼────────┐        nativeLibraryDir（唯一可执行目录）
        │  proot + loader │◄────── libproot.so / libprootloader.so（APK 内置）
        └───────┬────────┘
                │ fake root(-0) + bind(/dev /proc /sys /system /apex /sdcard ↔ /root/workspace)
     ┌──────────▼──────────┐
     │ files/linux/ubuntu  │  Ubuntu 24.04 arm64（rootfs 由镜像下载解压）
     │  /usr/local/bin/opencode  ← 官方 opencode-linux-arm64（npm 镜像 tarball 直取）
     └──────────┬──────────┘
                │ opencode web --hostname 127.0.0.1 --port 4096
     ┌──────────▼──────────┐
     │ HTTP API + Web UI   │  原生对话页与 WebView 共用同一端口
     └─────────────────────┘
```

几个关键点（也是踩过的坑）：

- **Android 10+ 的 W^X**：应用私有目录不能执行代码。所以 proot 与 loader 必须以 `lib*.so` 的形式放在
  `jniLibs`，由系统解压到 `nativeLibraryDir` 再执行；guest 程序则交给 proot 的 `PROOT_LOADER`
  做 userland exec，不经过内核的 execve 检查。
- **SONAME 对不上**：`libtalloc.so.2` 没法直接进 APK（AGP 只收 `*.so`）。运行前把
  `libtalloc.so` → `files/linux/lib/libtalloc.so.2`、`libandroidshmem.so` → `libandroid-shmem.so`
  复制一份给动态链接器。
- **DNS**：Android 没有 `/etc/resolv.conf`，每次安装/启动前从 `ConnectivityManager` 读 DNS 写进 rootfs。
- **硬链接**：app 私有目录（f2fs/ext4）支持硬链接，`--link2symlink` 只在探测失败时才启用。
- **apt 在 proot 里**：`APT::Sandbox::User=root`，否则 apt 会尝试切换到 `_apt` 用户而失败；
  源统一改写成经典 `sources.list`（去掉 deb822 的那份，避免双份源互相打架）。
- **不给系统添乱**：`--kill-on-exit` 保证 proot 一死带走整棵进程树；停止时再补一次
  `pkill -9 -f opencode` 兜底。
- **设备 Shell 桥**：Android 10+ 的普通应用既没有 shell 身份也不能执行系统命令，所以走 Shizuku 的
  「用户服务」：Shizuku 会把我们 APK 里的 `DeviceShellUserService`（**必须继承 AIDL 生成的 `Stub`**，
  并实现保留事务 `destroy() = 16777114`）加载到它的 shell/root 进程里，App 只通过 Binder 调用；
  容器里则由 `dexec`（curl + 随机 token + `X-Dexec-Timeout-Ms`）打到 `127.0.0.1:3090` 的本地 HTTP 桥。
- **文件安全**：tar 解压前校验条目名与**父目录真实路径**（防「软链逃逸」），硬链接源同样清洗；
  大文件下载尽量接上上游摘要（Ubuntu `SHA256SUMS` / Node `SHASUMS256.txt` / npm `dist.shasum`）；
  删除与体积统计一律不跟随符号链接（rootfs 里有 `var/run -> /run` 这类绝对链接）。

---

## 已验证 / 只能在真机验证的部分

开发机上已经实际构建并通过以下检查（Windows + JDK 25 + Gradle 9.5 + AGP 9.3.3）：

- **41 → 50 个 JVM 单元测试**全绿：服务端 JSON 解析（照着官方 `types.gen.d.ts` 写样本）、
  `opencode.json` 生成、环境变量映射、版本比较（含 `24.04.10 > 24.04.9` 这种坑）、
  **上游文件解析（Node index.json 的 `files` 字段、Ubuntu 目录列表、SHA256SUMS 文本）**、
  Markdown 解析、格式化输出；
- **打包自检**（`tools/verify-apk.mjs`）：4 个内置 ELF 与 `jniLibs` **逐字节一致**（proot 的入口点没有被
  strip 改写）、只含 `arm64-v8a`、容器脚本齐全；有 aapt2 时进一步核对合并后的 manifest
  （`extractNativeLibs=true`、前台服务类型 `specialUse`、各组件名）；并直接在 dex 里确认
  `DeviceShellUserService` / `IDeviceShellService` 存在（R8 精简包尤其关键）；
- **镜像源体检**（`tools/mirror-check.mjs`）：25 个镜像端点全部用真实请求验证过，
  验证不通过的（sjtu 的 ubuntu-cdimage、ustc 的 nodejs-release、tuna 的 npm）已从列表移除，
  另外补了 cernet / zju / bfsu / tencent 等可用源；
- 内置运行时全部来自 Termux 官方仓库（proot 5.1.107.96 / libtalloc 2.5.0 / libandroid-shmem 0.7），
  `tools/fetch-boot-runtime.mjs` 会校验 sha256 并写入 `jniLibs/boot-runtime.json`。

必须拿到真机才能验证的部分（CI 覆盖不到）：

- 容器能否正常起来、apt 是否能装包、以及最关键的：opencode 的 Bun 二进制在 proot 下能否正常跑；
- Shizuku 用户服务（`dexec`）的绑定与执行；
- 各家 ROM 的省电策略与 Android 12+ 幻影进程限制；
- R8 精简包的功能回归（所以默认发布的还是未混淆版本）。

## 1.1.0 修复记录

这一轮做了一次全量审查（容器层 / UI 层 / 服务与协议层三路并行），修掉的问题按严重度列几个代表性的：

| 严重度 | 问题 | 修复 |
| --- | --- | --- |
| P0 | 断点续传用 `RandomAccessFile(part, "r")` 打开后写入，续传必然失败 | 改成 `"rw"`，并校验 `Content-Range` 起点 |
| P0 | Shizuku 用户服务写成了 `android.app.Service`，Shizuku 只接受 AIDL `Stub` | 改为继承 `IDeviceShellService.Stub`，补上保留事务 `destroy() = 16777114` |
| P0 | 容器配置探测路径漏了 `root/`，装完重启就回到「未安装」 | 统一用 `Guest.OPENCODE_CONFIG`（= `/root/.config/...`）推导宿主路径 |
| P0 | 取消安装后立刻再点安装会起两套流水线同时删/解压同一 rootfs | 取消改为「请求 + 等旧任务真正结束」；下载与解压都带取消检查点 |
| P0 | 前台服务收集到状态流的初始 STOPPED 就 `stopSelf()`，刚起来就自杀 | 只由 `ACTION_STOP` 停止服务 |
| P1 | 命令超时/取消靠 `withTimeout`，拦不住阻塞的 `readLine`；且 stdin 永不关闭 → `dpkg`/`cat` 永久挂死 | 看门狗杀进程 + 立刻关闭子进程 stdin + 输出环形缓冲 |
| P1 | 安装失败后 `refresh()` 把 FAILED 与错误原因抹掉，用户「看不到发生了什么」 | 失败状态与错误文本落盘并在刷新时保留 |
| P1 | rootfs 先删后解压，中途失败会毁掉可用环境；下载包在自检前就删了 | 解压到 `ubuntu.new` 再原子替换；自检通过后才删下载包 |
| P1 | tar 里软链/硬链的目标没校验，恶意 tarball 可以写出沙箱 | 父目录真实路径必须在目标树内，否则跳过并计数 |
| P1 | 下载不做摘要校验 | 接上 Ubuntu `SHA256SUMS`、Node `SHASUMS256.txt`、npm `dist.shasum`（拿不到时降级为仅 HTTPS） |
| P1 | 自写 HTTP 桥按**字符**读 `Content-Length`，中文命令会卡 15 秒后空响应 | 改为按字节读 + 整体 UTF-8 解码，并处理 `Expect: 100-continue` |
| P1 | 设备桥改端口不重建监听；并发 sync 会把可用状态写成禁用 | 记录真实监听端口，变更即重建；`syncNow()` 加互斥锁 |
| P1 | `pkill -9 -f opencode` 会匹配到自己；健康检查超时不清理进程 | 用 `[o]pencode` 模式；超时后杀进程并复位状态 |
| P1 | 首页每秒重组、工作区解析在主线程、导出日志读写在主线程（ANR 风险） | 全部挪到 IO 线程 / 仅在服务运行时刷新 |
| P1 | 对话页：连点发送丢草稿、权限回复失败不回滚、`session.error` 串会话、SSE 空闲 30 秒被自己掐断 | 同步抢锁 + 失败回滚 + 会话过滤 + SSE 关闭读超时（重连后自动补拉历史） |
| P2 | 镜像里 Node 的 arm64 判定写错（`files` 是平台标识不是文件名）→ Node 步骤必失败 | 改为匹配 `linux-arm64`，并加单测锁住 |
| P2 | `deleteRecursively()` / `walkTopDown()` 会跟随符号链接（rootfs 里有 `var/run -> /run`） | 新增 `FileOps`：删除/统计都不跟随链接 |

任何一步失败时：安装页会显示该步的错误与可重试按钮，「终端 → 安装日志 / 运行日志」有完整输出。

## 常见问题

**服务启动超时 / 一启动就退出？**
看「终端 → 运行日志」。最常见的原因是没配 API Key（opencode 起得来但用不了），其次是内存不足。
可以在设置里打开「低内存模式」，它会关掉文件监视与 LSP 自动下载。

**Android 12+ 上服务偶尔被杀？**
那是系统的「幻影进程杀手」（限制每个应用的后台进程数）。把 App 加入电池白名单（首页会提示），
或者用 adb / Shizuku 执行：

```bash
adb shell settings put global settings_enable_monitor_phantom_procs false
```

**能装到 x86 模拟器上吗？**
不能。内置 proot 与 rootfs 都是 aarch64，`ndk.abiFilters` 也只保留 `arm64-v8a`。

**opencode 需要 Node 吗？**
不需要，opencode 本体是 Bun 编译出的单文件可执行程序。Node.js（安装第 4 步）是可选项，
留给插件、npm 项目和想在手机里跑 node 的场景。

**设备 Shell（`dexec`）怎么用？**
1. 装并启动 [Shizuku](https://shizuku.rikka.app/)（Android 11+ 可用无线调试在本机启动，不需要电脑）；
2. App →「设置 → 设备 Shell」打开开关，点「请求 Shizuku 授权」并允许；
3. 容器里就能用了：

```bash
dexec 'getprop ro.product.model'    # 设备型号
dexec 'dumpsys battery | head -20'  # 电池
dexec 'pm list packages | grep 星'  # 找应用
```

同一时间 App 会往容器写 `~/.config/opencode/skills/device-shell/SKILL.md`，
于是 opencode 的 agent 自己也会知道什么时候该用 `dexec`（技能按需加载）。
桥只监听 `127.0.0.1:3090`，每次开启会重新生成随机 token，关掉开关即失效。

**工作区里的中文文件名 / 大仓库？**
容器里的 git 已配置 `core.fileMode=false`、`safe.directory=*`。大仓库建议关掉快照（设置里可关），
否则 opencode 的内部 git 快照会占不少空间。

**想改成官方 Web UI 之外的原生体验？**
「对话」页就是原生实现：`GET /event` 的 SSE 事件流 + `prompt_async`，没有中间层，可自行扩展。

---

## 目录结构

```
app/src/main/java/dev/pocket/opencode/
├── core/            容器与安装：PocketPaths Proot GuestShell Installer Repos Downloader
│                    TarExtract FileOps（不跟随软链的删除/统计） ServerController NetworkBridge
│                    OpencodeConfig SettingsStore Versions UpstreamParsers（纯解析，有单测）
│                    Doctor（一键体检） ShizukuBridge + DeviceShellManager（设备 Shell 桥）
├── data/            服务端类型与 REST/SSE 客户端
├── service/         前台服务与通知 + DeviceShellServer（本地 HTTP 桥）+ DeviceShellUserService（Shizuku 侧 Stub）
├── ui/              Compose 界面：Home / Setup / Chat / Terminal / Web / Settings / Doctor + 自绘图标
└── util/

app/src/main/aidl/            Shizuku 用户服务的 Binder 接口（含 destroy 保留事务）
app/src/main/assets/scripts/  容器内执行的 shell 脚本（apt、工具、收尾）
app/src/main/assets/device-shell/  dexec 命令与 device-shell 技能模板
app/src/test/                 50 个 JVM 单元测试（解析 / 配置 / 版本 / 上游文件 / Markdown / 格式化）
tools/fetch-boot-runtime.mjs  拉取 Termux 官方 proot 运行时并校验，落到 jniLibs
tools/verify-apk.mjs          打包自检：内置 ELF 是否被改、ABI、容器脚本、manifest、Shizuku 类
tools/mirror-check.mjs        镜像源体检：25 个端点用真实请求逐个验证
```

## 第三方组件与许可

- [opencode](https://github.com/anomalyco/opencode)（MIT）：运行时下载，非随包分发。
- Termux `proot` / `libtalloc` / `libandroid-shmem`（GPL-2.0 / LGPL-3.0 / MIT 等，随包分发，
  由 `tools/fetch-boot-runtime.mjs` 从 Termux 官方仓库拉取并记录 sha256）。
- Ubuntu base rootfs：首次运行时下载，遵循 Ubuntu 各自许可。
- AndroidX、OkHttp、commons-compress、xz：见各自许可证。

本项目自身：MIT。
