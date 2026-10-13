#!/usr/bin/env node
/**
 * 拉取 Termux 官方 aarch64 运行时（proot 及其依赖），落地到 app/src/main/jniLibs/arm64-v8a。
 *
 * 为什么内置的是 Termux 的二进制：
 *   - proot 是 glibc/Bionic 混不进去的原生程序，只能跑 Bionic 版本；
 *   - Termux 官方仓库里的 proot 是社区长期验证过的 Android 构建；
 *   - 放进 jniLibs 后会在安装时被系统解压到 nativeLibraryDir（唯一可执行的应用目录），
 *     从而绕过 Android 10+ 对 app 私有目录的 W^X 限制。
 *
 * 依赖关系（已核对 ELF）：
 *   proot            -> libtalloc.so.2, libandroid-shmem.so, libc.so   (PIE, interp=/system/bin/linker64)
 *   libtalloc.so.2.5.0 -> libc.so                                     (SONAME=libtalloc.so.2)
 *   libandroid-shmem.so -> liblog.so, libc.so
 *   loader            -> 静态 aarch64 可执行文件（proot 的 userland exec 助手，静态所以可直接 exec）
 *
 * 由于 jniLibs 里只允许 `*.so` 结尾的文件名，talloc / android-shmem 会以“改名”的形式随包，
 * 运行期再由 App 复制成正确的 SONAME 文件名放到私有目录的 lib/ 下，供动态链接器解析。
 *
 * 用法：
 *   node tools/fetch-boot-runtime.mjs            # 下载并写入 jniLibs
 *   node tools/fetch-boot-runtime.mjs --check    # 只校验已存在的文件是否与远端一致
 */
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import zlib from "node:zlib";

const ROOT = path.resolve(import.meta.dirname, "..");
const OUT_DIR = path.join(ROOT, "app", "src", "main", "jniLibs", "arm64-v8a");
const CACHE_DIR = path.join(ROOT, "tools", "cache", "termux");
const MANIFEST = path.join(ROOT, "app", "src", "main", "jniLibs", "boot-runtime.json");

const REPOS = [
  "https://packages.termux.dev/apt/termux-main",
  "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
  "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
];

/** 需要随包的 Termux 软件包。 */
const PACKAGES = ["proot", "libtalloc", "libandroid-shmem"];

/** deb 内的文件 -> jniLibs 文件名。 */
const ARTIFACTS = [
  ["usr/bin/proot", "libproot.so"],
  ["usr/libexec/proot/loader", "libprootloader.so"],
  ["usr/lib/libtalloc.so.2.5.0", "libtalloc.so"],
  ["usr/lib/libandroid-shmem.so", "libandroidshmem.so"],
];

const checkOnly = process.argv.includes("--check");

function log(...args) {
  console.log("[boot-runtime]", ...args);
}

async function download(url, dest, { tries = 3 } = {}) {
  if (fs.existsSync(dest) && !isStale(dest)) return dest;
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  let lastError;
  for (let attempt = 1; attempt <= tries; attempt++) {
    try {
      const response = await fetch(url, { redirect: "follow" });
      if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
      const buffer = Buffer.from(await response.arrayBuffer());
      fs.writeFileSync(dest, buffer);
      return dest;
    } catch (error) {
      lastError = error;
      log(`下载失败(${attempt}/${tries}) ${url}: ${error.message}`);
      await new Promise((r) => setTimeout(r, 1500 * attempt));
    }
  }
  throw new Error(`无法下载 ${url}: ${lastError?.message}`);
}

function isStale(file) {
  return fs.statSync(file).size === 0;
}

function sha256(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

/** 解析 Debian 的 `ar` 归档（deb 就是 ar），返回其中的成员。 */
function parseAr(buffer) {
  const magic = buffer.subarray(0, 8).toString("ascii");
  if (magic !== "!<arch>\n") throw new Error("不是 ar 归档");
  const members = [];
  let offset = 8;
  while (offset + 60 <= buffer.length) {
    const header = buffer.subarray(offset, offset + 60).toString("ascii");
    const size = parseInt(header.substring(48, 58).trim(), 10);
    const name = header.substring(0, 16).trim().replace(/\/$/, "");
    const dataStart = offset + 60;
    members.push({ name, data: buffer.subarray(dataStart, dataStart + size) });
    offset = dataStart + size + (size % 2);
  }
  return members;
}

function extractTarXz(buffer, destDir) {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "pocket-deb-"));
  const tempFile = path.join(tempDir, "data.tar.xz");
  fs.writeFileSync(tempFile, buffer);
  fs.mkdirSync(destDir, { recursive: true });
  // Windows 的 bsdtar 在少数条目上会报 “Invalid argument”
  //（文档目录里的 hardlink / 包内软链），这些都不是我们要的文件，
  // 直接排除掉；Linux 的 GNU tar 同样支持这些参数。
  const args = [
    "-xf", tempFile, "-C", destDir,
    "--exclude=*/share/doc/*",
    "--exclude=*/share/man/*",
    "--exclude=*usr/lib/libtalloc.so",
    "--exclude=*usr/lib/libtalloc.so.2",
    "--exclude=*usr/lib/pkgconfig/*",
    "--exclude=*usr/include/*",
  ];
  try {
    execFileSync("tar", args, { stdio: "inherit" });
  } catch (error) {
    // 只要目标文件之后能拿到，就不因为个别条目失败而中断
    log(`解包 ${path.basename(destDir)} 时有条目被跳过（${error.message.split("\n")[0]}），继续`);
  }
  fs.rmSync(tempDir, { recursive: true, force: true });
}

/** 在目录下按后缀找文件（Termux 包内路径带安装前缀）。 */
function findBySuffix(root, suffix) {
  if (!fs.existsSync(root)) return null;
  const wanted = suffix.split("/").join(path.sep);
  const stack = [root];
  while (stack.length) {
    const current = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(current, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) {
        stack.push(full);
      } else if (full.endsWith(path.sep + wanted)) {
        return full;
      }
    }
  }
  return null;
}

function parsePackagesIndex(text) {  const entries = new Map();
  for (const block of text.split(/\n\n+/)) {
    const fields = new Map();
    for (const line of block.split("\n")) {
      const match = /^([A-Za-z-]+): (.*)$/.exec(line);
      if (match) fields.set(match[1], match[2]);
    }
    if (fields.has("Package")) entries.set(fields.get("Package"), fields);
  }
  return entries;
}

async function fetchIndex(repo) {
  const url = `${repo}/dists/stable/main/binary-aarch64/Packages.gz`;
  const response = await fetch(url, { redirect: "follow" });
  if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
  const gz = Buffer.from(await response.arrayBuffer());
  return parsePackagesIndex(zlib.gunzipSync(gz).toString("utf8"));
}

async function main() {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.mkdirSync(CACHE_DIR, { recursive: true });

  // 1) 找一个可用的 Termux 源并取回索引
  let repo = null;
  let index = null;
  for (const candidate of REPOS) {
    try {
      index = await fetchIndex(candidate);
      repo = candidate;
      log("使用索引源:", candidate);
      break;
    } catch (error) {
      log("索引源不可用:", candidate, error.message);
    }
  }
  if (!index || !repo) throw new Error("所有 Termux 索引源都不可用");

  // 2) 下载 deb 并校验 SHA256
  const debFiles = [];
  for (const name of PACKAGES) {
    const entry = index.get(name);
    if (!entry) throw new Error(`索引中缺少软件包 ${name}`);
    const relative = entry.get("Filename");
    const expected = entry.get("SHA256");
    const local = path.join(CACHE_DIR, path.basename(relative));
    await download(`${repo}/${relative}`, local);
    const digest = sha256(fs.readFileSync(local));
    if (expected && digest !== expected) {
      log(`摘要不符，重新下载 ${name}`);
      fs.rmSync(local);
      await download(`${repo}/${relative}`, local);
      if (sha256(fs.readFileSync(local)) !== expected) throw new Error(`${name} 摘要校验失败`);
    }
    debFiles.push({ name, version: entry.get("Version"), file: local, sha256: digest });
    log(`${name} ${entry.get("Version")} -> ${path.basename(local)}`);
  }

  // 3) 解包 deb -> data.tar.xz -> 文件系统树
  const unpackRoot = path.join(CACHE_DIR, "unpacked");
  fs.rmSync(unpackRoot, { recursive: true, force: true });
  for (const item of debFiles) {
    const members = parseAr(fs.readFileSync(item.file));
    const data = members.find((m) => m.name.startsWith("data.tar"));
    if (!data) throw new Error(`${item.name}: deb 缺少 data.tar`);
    extractTarXz(data.data, path.join(unpackRoot, item.name));
  }

  // 4) 拷贝到 jniLibs
  const manifest = { generatedAt: new Date().toISOString(), source: repo, packages: debFiles, files: [] };
  for (const [relative, target] of ARTIFACTS) {
    let source = null;
    for (const item of debFiles) {
      // deb 里的路径带着 Termux 的安装前缀（data/data/com.termux/files/…），
      // 这里按后缀匹配，不硬编码前缀。
      const candidate = findBySuffix(path.join(unpackRoot, item.name), relative);
      if (candidate) {
        source = candidate;
        break;
      }
    }
    if (!source) throw new Error(`解包目录中找不到 ${relative}`);
    const bytes = fs.readFileSync(source);
    const digest = sha256(bytes);
    const targetPath = path.join(OUT_DIR, target);
    if (checkOnly) {
      if (!fs.existsSync(targetPath) || sha256(fs.readFileSync(targetPath)) !== digest) {
        throw new Error(`${target} 与 Termux 远端不一致，请运行 fetch-boot-runtime.mjs`);
      }
    } else {
      fs.writeFileSync(targetPath, bytes);
      fs.chmodSync(targetPath, 0o644);
    }
    manifest.files.push({ target, size: bytes.length, sha256: digest });
    log(`${checkOnly ? "校验" : "写入"} ${target} (${bytes.length} 字节)`);
  }

  if (!checkOnly) fs.writeFileSync(MANIFEST, JSON.stringify(manifest, null, 2) + "\n");
  log("完成：内置运行时已就绪");
}

main().catch((error) => {
  console.error("[boot-runtime] 失败:", error.message);
  process.exit(1);
});
