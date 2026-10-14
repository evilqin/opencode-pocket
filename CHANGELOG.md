# 变更记录

本项目遵循“每个版本都能装、都能回滚”的原则：所有 APK 与源码标签一一对应，`vX.Y.Z` 标签由 CI 自动构建并发布。

## 1.3.0

### 新增
- **CI 里真的把容器跑一遍**（`tools/ci-runtime-smoke.sh` + workflow 的 `smoke` job）：
  在 Linux runner 上用 proot 完整走「下载 Ubuntu base rootfs 并校验 SHA256 → 用与 App 一致的
  proot 参数启动 → 执行内置的容器脚本并断言目录/npmrc/banner → 走镜像直取 Node 与 opencode
  （校验摘要）→ `opencode --version`（头号未知）→ 起 `serve`/`web` → 健康检查 / 会话 API /
  providers / agent / SSE 事件流 / Basic 鉴权 401 / abort 与删除 → `dexec` 的错误路径」。
  **发布闸门**：`release` 现在同时依赖 `build` 与 `smoke`，每个 tag 都必须先通过真实运行时验证
- **「本次改动」视图**：会话页标题栏进入，列出 agent 改过的文件与 `+/-` 统计，点进去看统一差分
  （本地 LCS 计算、彩色高亮、可复制差分或新内容；服务端只给统计时优雅退化为提示）
- **测试 120 → 142 个**：统一差分（11）、proot 参数（6）、真实软链/硬链安全边界（4，Windows 跳过、Linux CI 会跑）等

### 修复
- **P0（真机级）proot 参数写成短选项 `-L`**：冒烟测试第一次运行就暴露 Ubuntu 的 proot 5.1.0
  不认它；核对 APK 内置的 Termux proot ELF 后确认应使用长选项 `--sysvipc`（`--bind` 同理统一）。
  若真机上同样如此，容器会以 `unknown option '-L'` 直接启动失败 —— 参数单独抽成 `ProotArgs`
  并用单测钉住「只用长选项」的约定
- **内置 proot 的能力纳入 CI 把关**：`tools/fetch-boot-runtime.mjs --check` 现在会扫描 proot
  的选项表，核对 App 依赖的 6 个长选项（`--rootfs/--cwd/--bind/--kill-on-exit/--link2symlink/--sysvipc`）
  都在；换 proot 版本会先在这里失败，而不是等到手机上
- **tar 解压改用 `java.nio.file`**：软链/硬链在 JVM 单测里是**真实的**链接，
  「先放软链、再写同名文件」的逃逸防护与「目录软链被替换」终于有可执行的回归用例
- `chmod` 在无 `Os.chmod` 的环境退化为 POSIX 权限设置，不再静默丢掉可执行位
- 冒烟脚本按本机 proot 的能力探测选项（缺哪个跳过哪个并打警告），不再假设某一种构建

### 供应链
- 所有 GitHub Actions 固定到 commit SHA（附 `# v4` 之类的版本注释），避免上游 tag 漂移

## 1.2.0

### 安全（这一轮的重点）
- **本机服务加口令**：`opencode` 服务与设备桥都只监听 127.0.0.1，但 loopback 对**同机所有 App** 开放；
  现在启动服务会自动生成随机口令并通过 `OPENCODE_SERVER_PASSWORD` 注入，App 内所有客户端（对话 / 网页 / 体检）
  都带 Basic 认证，WebView 自动回填 —— 别的应用再也无法直接驱动你的 agent
- **默认收紧权限策略**：`permission.bash` / `permission.edit` 默认从 `allow` 改为 **`ask`**，
  容器里的 agent 改文件、跑命令前都会先问你一句
- **共享存储默认不挂**：不再无条件把 `/sdcard` 挂进容器；只有显式打开「把整个共享存储挂进容器」才会挂
  （容器默认只能看到你选的工作区）
- **设备桥危险命令防护**：拦截 `rm -rf /`、`pm uninstall`、`dd of=/dev/block/…`、`settings put`（幻影进程那条除外）
  等 17 类命令，命中直接返回 403 并说明原因；每次调用（含被拦）都写审计日志，设置里可查看
- **工作区路径校验**：只允许共享存储里的具体子目录，`/`、`/data`、`/sdcard` 这类会被拒绝
- **apt 不再降级**：去掉 `[trusted=yes]` 与明文 http 回退，没 CA / 没 keyring 就直接报错让用户换源
- **网络安全配置**：显式只放行 `127.0.0.1` / `localhost` / `::1` 的明文，其它域名一律禁止
- **日志导出脱敏**：导出前自动打码 `sk-…`、`*_API_KEY=`、`Bearer`/`Basic` 头、桥令牌与服务口令
- **供应链**：CI 的运行时校验改成**离线比对仓库里的 `boot-runtime.json`**（原来是把远端下载覆盖后自比，等于没验）；
  清单里不再写本机绝对路径；可选出正式签名（配 secrets 即可），没配时缓存 debug keystore 保证覆盖安装签名一致

### 修复（回归审查发现，含上一轮引入的问题）
- **P0** 非续传下载用了非法模式 `RandomAccessFile(…,"w")`，导致所有全新下载必然失败 → 统一 `"rw"`
- **P0** 清理残留进程用的 `pkill -f opencode` 会杀掉 App 自己（包名里就带 opencode，且 `/proc` 被原样 bind 进容器）
  → 改成按 `guest` 侧记录的 pid 精确杀 + 收窄模式兜底
- **P1** rootfs 自检在「换上新环境之后」才做，自检失败旧环境已被删 → 先备份、自检通过才删，失败自动回滚
- **P1** 失败步骤会被磁盘探测改写成「完成」（产物存在但自检没过）→ 失败优先于自愈，错误与原因都留住
- **P1** tar 里「先放软链、再放同名文件」可把内容写到树外 → 写文件/建目录前先 lstat 并 unlink 软链
- **P1** 取消/超时拦不住阻塞读取：`GuestShell` 的看门狗随调用方一起被取消，
  `run()` 可能永不返回 → 看门狗与取消监视改用独立作用域，200ms 轮询后强杀进程
- **P1** SSE 事件流的取消不生效（`runCatching` 吞掉 CancellationException、读超时为 0）→ 改 `callbackFlow` + `call.cancel()`
- **P1** WakeLock 只在状态跳变时续期，长跑 60 分钟后静默过期 → 独立续期协程（30 分钟一次）
- **P1** 发布包每次用 runner 现场生成的 debug keystore，签名可能变 → 可选正式签名 + 缓存 debug keystore
- **P2** `rm -rf` 规则过于宽泛（误伤 `rm -f /data/local/tmp/...`）；`LogRedactor` 会把 `DEEPSEEK_API_KEY=` 前缀吃掉
- **P2** 精简包构建产出与标准包同名，artifact 里只剩精简包 → 分别暂存、分别自检
- **P2** 桥的 `timeoutMs` 缺省被夹成 1 秒、裸 LF 请求头卡 15 秒、`stop()` 后线程池不可复用、Bearer 前缀不严格
- **P2** 取消后步骤卡在「进行中」；`sizeOf` HEAD 无长度时不回退 Range；同一文件白算一遍哈希
- **P2** 桥关闭时仍在生成并持久化 token；容器文件写入失败被静默吞掉 → 写进状态信息
- **P2** 通知：失败态不可操作、停止后残留旧通知 → 修正 ongoing / 可停止语义并在停止时取消
- **P2** 开机自启只等 6 秒（`goAsync` 只有约 10 秒预算）；桥的超时上限与 Shizuku 侧统一为 10 分钟

### 新增
- **开机自动启动**（默认关闭；Android 12+ 后台启动前台服务受限，失败不影响手动启动）
- **应用快捷方式**：长按图标直接「启动服务」/「打开网页」
- **测试 50 → 120 个**：新增安装状态合并、SSE 解析、设置编解码、摘要与文件操作、tar 安全边界、
  危险命令防护、日志脱敏、工作区策略等 8 组
- **自检更严**：`verify-apk.mjs` 现在还会核对 APK 的 `versionName`/`versionCode` 与
  `app/build.gradle.kts` 是否一致；CI 发布前再校验版本相对最新 Release 单调递增
- **CHANGELOG.md**：从 1.0.0 起的完整变更记录

## 1.1.1

### 新增
- CI 支持「打标签即发布」：推送 `vX.Y.Z` 标签会自动构建三份 APK 并发布 Release（发布前校验标签与 `versionName` 一致）

### 修复
- CI 产物命名冲突：标准包与精简包写入同一路径，导致 artifact 里只剩精简包
- CI 平台包名写错（`platforms;android-37` 实际叫 `android-37.0`）
- workflow 首行大小写错误（`Name:` → `name:`），此前文件根本没被解析
- `gradlew` 缺少可执行位（Windows 提交导致 CI `exit 126`）

## 1.1.0

### 修复（按严重度）
- **P0** 断点续传用只读句柄写入，续传必然失败 → 改用 `"rw"` 并校验 `Content-Range`
- **P0** Shizuku 用户服务类型错误（必须是 AIDL `Stub` 而非 `android.app.Service`），此前 `dexec` 整条链路不可用
- **P0** 容器配置探测路径漏了 `root/` 前缀，装完重启会误判为「未安装」
- **P0** 取消安装后立刻重试会起两套流水线同时删/解压同一 rootfs
- **P0** 前台服务因状态流初值 `STOPPED` 而自杀
- **P1** 命令超时/取消拦不住阻塞读取、子进程 stdin 永不关闭导致 `dpkg`/`cat` 挂死
- **P1** 安装失败状态与错误原因被刷新抹掉
- **P1** rootfs 先删后解压（中途失败毁掉可用环境）、下载包在自检前被删
- **P1** tar 软链/硬链目标未校验（沙箱逃逸风险）、设备节点处理错误
- **P1** 下载无摘要校验 → 接上 Ubuntu `SHA256SUMS` / Node `SHASUMS256.txt` / npm `dist.shasum`
- **P1** 自写 HTTP 桥按字符读 `Content-Length`（中文命令卡 15 秒）、`/health` 无鉴权、端口变更不重建
- **P1** `pkill -9 -f opencode` 会匹配到自己；健康检查超时不清理进程
- **P1** 对话页连点丢草稿、权限回复失败不回滚、`session.error` 串会话、SSE 空闲被自己掐断
- **P2** 镜像里 Node 的 arm64 判定写错（Node 步骤必失败）
- **P2** `deleteRecursively()` / `walkTopDown()` 跟随符号链接（rootfs 有 `var/run -> /run`）
- 首页每秒重组、工作区解析与日志导出在主线程、WebView 泄漏、Markdown 斜体吃掉加粗等一批体验问题

### 新增
- 一键体检（12 项只读检查，可复制报告）
- 设备 Shell 桥（Shizuku 用户服务 + 本地 HTTP 桥 + `dexec` + 技能文件）
- 安装日志「清屏」真正清源缓冲；镜像源按实测体检结果更新（25 个端点）
- 打包自检脚本 `tools/verify-apk.mjs`、镜像体检脚本 `tools/mirror-check.mjs`
- 单元测试 0 → 50

## 1.0.0

- 首个版本：内置 Termux 官方 proot 运行时（jniLibs），一键安装 Ubuntu 24.04 + opencode
- 六步安装流水线、四类镜像真实测速、断点续传
- 原生对话页（REST + SSE）、官方 Web UI（WebView）、类 shell 终端与双日志
- 工作区挂载（手机目录 ↔ `/root/workspace`）、13 个 provider 的密钥与模型设置
- 前台服务常驻 + 通知栏状态、一键体检、GitHub Actions 三份产物（标准 / R8 精简 / 调试）
