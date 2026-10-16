# OpenCode 口袋（OpenCode Pocket）

> **简体中文** | [English](README.en.md)

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
| 本次改动 | 会话页点开：列出 agent 改过的文件与 `+/-` 统计，逐行看统一差分（本地计算，可复制差分或新内容） |
| Web UI | 内嵌 WebView 打开 `opencode web` 的官方界面，可跳外部浏览器 |
| 终端 | 类 shell 的命令执行（cwd 保持），快捷命令；安装日志 / 运行日志各一份，可复制可分享 |
| 设置 | 12 个常用 provider 的 API Key、默认模型、端口、权限策略、低内存模式、工作区、主题、维护工具 |
| 工作区 | 手机上的真实目录（默认 `/sdcard/OpenCode`）挂进容器 `/root/workspace`；共享存储默认**不**整体挂载 |
| 一键体检 | 12 项只读检查（架构/运行时/rootfs/git/opencode/域名解析/空间/端口/密钥/权限…），结果可一键复制 |
| 设备 Shell 桥 | 可选：通过 **Shizuku** 让容器里的 agent 用 `dexec` 在手机本体执行命令（含危险命令防护与审计日志） |
| 安全默认 | 本机服务带随机口令（`OPENCODE_SERVER_PASSWORD`）、`share: disabled`、`autoupdate: false`、权限策略默认 `ask`、密钥只写应用私有目录、零遥测 |

---

## 安装

### 方式一：直接装 APK

1. 到 [Releases](../../releases) 下载 `app-release.apk`（或自己构建，见下）。
2. 手机需要 **ARM64**，Android **11（API 30）及以上**。
3. 安装后打开 → 「开始安装」→ 选镜像 → 等 5 分钟左右。

> 三份构建产物都在 [Releases](../../releases) 里（当前 1.4.0）：
> `*-arm64.apk`（标准包，未混淆，最稳）、
> `*-arm64-slim.apk`（R8 精简包，约 4 MB，体积小但需要真机回归一遍）、
> `*-debug.apk`（调试包，日志最全）。

### 方式二：自己构建

```bash
git clone <this-repo> && cd opencode-pocket

# 1) 拉取内置的 proot 运行时（Termux 官方包，约 400KB）
node tools/fetch-boot-runtime.mjs

# 2) 单元测试 + 构建
./gradlew :app:testDebugUnitTest    # 161 个纯逻辑用例（解析 / 配置 / 版本 / 上游文件 / tar 安全 / 差分 / proot 参数 / 危险命令防护）
./gradlew :app:assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # 默认用 debug 签名，可直接安装

# 可选：R8 精简包（4 MB 左右；会重写代码，务必真机回归）
./gradlew :app:assembleRelease "-Ppocket.minify=true"

# 可选：在 Linux 上把容器配方整条跑一遍（CI 也会跑，见下）
bash tools/ci-runtime-smoke.sh
```

**发布新版本**：改 `app/build.gradle.kts` 里的 `versionCode` / `versionName`，然后推一个同名标签即可自动构建并发布 Release（CI 会先校验 tag 与 versionName 一致，并且**必须先通过容器冒烟测试**）：

```bash
git tag v1.4.1 && git push origin v1.4.1
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
- **不给系统添乱**：`--kill-on-exit` 保证 proot 一死带走整棵进程树；停止服务时按容器内记录的
  pid 精确清理（**不用** `pkill -f opencode`——包名里就带 opencode，会误杀 App 自身）。
- **proot 参数只用长选项**：不同构建支持的选项名不一样（Ubuntu 24.04 的 proot 5.1.0 就不认 `-L`），
  所以统一用 `--sysvipc` / `--bind` 这类长选项，并由 CI 扫描内置二进制逐一复核（见下）。
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

- **161 个 JVM 单元测试**全绿：服务端 JSON 解析（照着官方 `types.gen.d.ts` 写样本）、
  `opencode.json` 生成、环境变量映射、版本比较（含 `24.04.10 > 24.04.9` 这种坑）、
  **上游文件解析（Node index.json 的 `files` 字段、Ubuntu 目录列表、SHA256SUMS 文本）**、
  Markdown 解析、格式化输出；以及**安装状态合并 / SSE 解析 / 设置编解码 / 摘要与文件操作 /
  tar 安全边界（含真实软链与硬链）/ 危险命令防护 / 日志脱敏 / 工作区策略 / 统一差分 / proot 参数**；
- **打包自检**（`tools/verify-apk.mjs`）：4 个内置 ELF 与 `jniLibs` **逐字节一致**（proot 的入口点没有被
  strip 改写）、只含 `arm64-v8a`、容器脚本齐全；有 aapt2 时进一步核对合并后的 manifest
  （`extractNativeLibs=true`、前台服务类型 `specialUse`、各组件名）；并直接在 dex 里确认
  `DeviceShellUserService` / `IDeviceShellService` 存在（R8 精简包尤其关键）；
- **镜像源体检**（`tools/mirror-check.mjs`）：25 个镜像端点全部用真实请求验证过，
  验证不通过的（sjtu 的 ubuntu-cdimage、ustc 的 nodejs-release、tuna 的 npm）已从列表移除，
  另外补了 cernet / zju / bfsu / tencent 等可用源；
- 内置运行时全部来自 Termux 官方仓库（proot 5.1.107.96 / libtalloc 2.5.0 / libandroid-shmem 0.7），
  `tools/fetch-boot-runtime.mjs` 会校验 sha256、写入 `jniLibs/boot-runtime.json`，**并扫描 proot
  的选项表复核 App 依赖的 6 个长选项都在**。

### CI 里真的把容器跑一遍（1.3.0 新增）

`smoke` 任务在 Linux runner 上用 proot 把整条链路跑通，**并且是发布闸门**（`release` 依赖它）：

| 步骤 | 验证什么 |
| --- | --- |
| 下载 `ubuntu-base` rootfs + `SHA256SUMS` | 镜像目录结构没变、摘要校验路径可用 |
| 用与 App 一致的 proot 参数启动（`--sysvipc --kill-on-exit -0 --rootfs --cwd --bind`） | 参数名与传值方式对得上真实 proot（**曾抓出 `-L`、`--bind x` 两个坑**） |
| 执行仓库里的 `profile.sh` / `setup-env.sh` | 容器脚本能跑、目录 / npmrc / banner / locale 都对 |
| 执行 `setup-apt.sh` + `install-tools.sh` | apt 源与 keyring、**apt + dpkg 在 proot 假 root 下真的能装包** |
| `git init/commit` | 容器里的 git 可用（opencode 的快照与回滚依赖它） |
| 走镜像直取 Node 与 opencode（`SHASUMS256` / `dist.shasum` 校验） | 「从镜像直取二进制」这条路可行，包校验字段确实存在 |
| `opencode --version` | **Bun 二进制在 proot 下能不能跑**（整个项目最大的未知）——✅ 通过 |
| 起 `serve` 与 `web`，跑健康检查 / 会话 / providers / agent / SSE / 401 鉴权 / abort / 删除 | 原生客户端依赖的 REST + SSE 契约与口令注入成立；`web` 根路径返回 `200 text/html`（WebView 方案成立） |
| `dexec` 在未启用 / 桥不可达时的报错 | 设备桥脚本的错误路径可读 |

> 与真机的差异：CI 用的是发行版打包的 proot（Ubuntu 24.04 是 **5.1.0**，2016 年的版本），
> 手机里内置的是 **Termux 的 proot 5.1.107.96**（含 `statx` / `openat2` / `faccessat2` 等新系统调用）。
> 个别依赖新系统调用的程序在 CI 上会失败而真机正常 —— 例如 Node 24 自带的 npm 在 CI 里
> 报 `Cannot find module …/walk-up-path/…`（文件其实在、也能读），手机上大概率没这个问题；
> 即使有，App 也不再因此中断安装（npm 只记一条日志）。

必须拿到真机才能验证的部分（CI 覆盖不到）：

- 容器在 **Android 上**能否起来（Android 的 W^X、phantom-killer、各家 ROM 的省电策略都在 CI 之外）；
- Shizuku 用户服务（`dexec`）的绑定与执行；
- apt 在手机侧网络下装包、>60 分钟长跑与 SSE 泄漏、覆盖安装签名矩阵；
- R8 精简包的功能回归（所以默认发布的还是未混淆版本）。

## 1.4.0 重点（完整见 [CHANGELOG](CHANGELOG.md)）

- **安全加固专项**（第四轮审查 13 条全部落地）：危险命令防护不可绕写（规范化 + 分段判定 +
  结构性拦截 + fail-closed）；桥令牌与防护开关实时生效；WebView 只对目标服务端口回填口令；
  工作区策略拒绝 `.`/`..` 与 `Android/data|obb` 并 canonicalize；审计日志防 CR/LF 伪造；
  dexec 令牌不再经过命令行
- **服务生命周期**：启动/停止互斥 + 世代号（快速连点不再状态错乱）；清理命令先核对 `/proc` cmdline
  再杀（防 pid 复用误杀）；WakeLock 全路径释放（不再拖到 60 分钟超时）
- **安装管线**：`.part` 按来源隔离（换镜像不再拼出坏文件）；无长度且无摘要时拒绝「成功」；
  写容器文件前清软链；CA 半成品清理；空间预检统一 2 GiB；tar 硬链接防逃逸 / 截断报错 / 权限位保真
- **UI/UX**：停止服务与重置口令二次确认；聊天页状态跨旋转保留 + 返回键回列表 + 长按复制 + 智能吸底；
  差分计算移出主线程；终端状态保留
- 单元测试 148 → 161

## 1.3.0 重点（完整见 [CHANGELOG](CHANGELOG.md)）

- **CI 里真的把容器跑一遍**：Linux + proot 全流程（rootfs → 脚本 → apt/dpkg → Node/opencode → 服务与 API → web UI），
  并作为发布闸门。第一次跑就连着抓出**三个真机级问题**：
  1. proot 短选项 `-L` 在部分构建里不存在（容器会以 `unknown option` 直接启动失败）
  2. 长选项必须写成 `--bind=xxx` 一个 argv 元素（`--bind /dev` 被拒绝）
  3. 容器里没有 CA 证书导致 https 的 apt 源全量失败，而 `apt-get update` 仍然返回 0
- **统一改用长选项**（`--sysvipc` / `--bind=`），参数抽成 `ProotArgs` 由单测钉住，
  CI 还会扫描内置 proot 二进制复核这些选项名
- **新增「本次改动」视图**：文件列表 + 增删统计 + 本地计算的彩色统一差分
- **tar 解压改用 `java.nio.file`**：软链/硬链防护在单测里可验证（不再是空转）
- 单元测试 120 → 148；GitHub Actions 全部固定到 commit SHA

### 手机端验收清单（下一步要做的）

装了 1.4.0 之后，按这个顺序过一遍最省事（有问题就把「设置 → 导出日志」发我）：

1. **安装环境**：安装页 →「全线测速」→「一键安装」→ 4 步都过（重点看第 3 步 `apt` 与第 4 步 `opencode`）；
2. **服务**：首页 →「启动服务」→ 状态变绿，通知栏出现「OpenCode 服务」；
3. **配 Key**：设置 → 模型与密钥 → 填一个 provider 的 Key →「保存并重启服务」；
4. **对话**：对话页 → 新建会话 → 发一句「你好」→ 应该看到流式回复；
5. **改动视图**：让 agent 写一个文件（例如「在 workspace 里创建 hello.txt」）→ 会话页右上角「本次改动」→ 应看到文件与增删行；
6. **终端**：终端页 `opencode --version`、`git --version`、`cd /root/workspace && ls`；
7. **网页**：首页 →「打开网页」→ 应看到 opencode 官方 Web UI；
8. **设备桥（可选）**：设置里开启设备 Shell（需装 Shizuku 并授权）→ 容器里 `dexec 'getprop ro.product.model'`；
9. **长跑**：让 agent 跑一个 10 分钟以上的任务，看通知栏状态、进度与「停止」是否正常。

## 1.2.0 重点

- **本机服务加随机口令**：loopback 对同机所有 App 开放，现在 opencode 服务与设备桥都必须带口令/Bearer token
- **默认权限策略改为 `ask`**，并且**不再默认把整个 /sdcard 挂进容器**
- **设备桥危险命令防护 + 审计日志**；导出日志自动打码密钥与口令
- 修复上一轮引入的 P0：非续传下载用非法模式、pkill 误杀 App 自身；rootfs 自检失败会回滚
- 单元测试 50 → 120；CI 增加版本单调校验与可选正式签名

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

**服务口令是干什么的？**
opencode 服务监听 `127.0.0.1`，而 Android 的 loopback 对**同机所有 App** 开放 —— 不加口令的话，任何应用都能驱动你的 agent、读你的会话。所以 App 首启会生成一个随机口令，通过 `OPENCODE_SERVER_PASSWORD` 注入服务，App 自己的对话页 / 网页页 / 体检都带 Basic 认证（用户名固定 `opencode`）。你想用别的客户端连它（例如 `adb forward` 到电脑），在「设置 → 服务 → 服务口令」复制口令即可；也可随时点「重新生成服务口令」（需重启服务生效）。

**容器能看到我的相册和微信导出的文件吗？**
默认不能。容器只会挂上你选的工作区目录（`/root/workspace` ↔ 手机上的某个目录）；只有打开「设置 → 工作区 → 把整个共享存储挂进容器」，才会挂载 `/sdcard`。

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
app/src/test/                 161 个 JVM 单元测试（解析 / 配置 / 版本 / 上游文件 / Markdown / 格式化）
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
