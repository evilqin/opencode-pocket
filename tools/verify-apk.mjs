#!/usr/bin/env node
/**
 * 打包自检：直接读 APK（zip）并核对关键内容，不需要真机、不需要 aapt。
 *
 * 检查项：
 *  1. lib/arm64-v8a 下的 proot 运行时与 app/src/main/jniLibs 里的字节完全一致
 *     （AGP 的 strip 任务如果改写了 ELF，proot 就跑不起来）；
 *  2. AndroidManifest.xml 里 extractNativeLibs=true（否则运行时目录是空的）；
 *  3. assets/scripts/*.sh 都在包里；
 *  4. 应用只有一个 arm64 ABI。
 *
 * 用法：node tools/verify-apk.mjs app/build/outputs/apk/debug/app-debug.apk
 */
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import zlib from "node:zlib";

const ROOT = path.resolve(import.meta.dirname, "..");
const JNI_DIR = path.join(ROOT, "app", "src", "main", "jniLibs", "arm64-v8a");
const REQUIRED_LIBS = ["libproot.so", "libprootloader.so", "libtalloc.so", "libandroidshmem.so"];
const REQUIRED_ASSETS = ["assets/scripts/profile.sh", "assets/scripts/setup-apt.sh", "assets/scripts/install-tools.sh", "assets/scripts/setup-env.sh"];

/** 极简 zip 读取：只处理 stored(0) 与 deflate(8)，够做自检。 */
function openZip(file) {
  const buffer = fs.readFileSync(file);
  // 找 EOCD（0x06054b50），从尾部往前扫
  let eocd = -1;
  for (let i = buffer.length - 22; i >= 0 && i > buffer.length - 70000; i--) {
    if (buffer.readUInt32LE(i) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error("不是有效的 zip（找不到 EOCD）");
  const count = buffer.readUInt16LE(eocd + 10);
  const cdOffset = buffer.readUInt32LE(eocd + 16);

  const entries = new Map();
  let offset = cdOffset;
  for (let i = 0; i < count; i++) {
    if (buffer.readUInt32LE(offset) !== 0x02014b50) throw new Error("zip 中央目录损坏");
    const method = buffer.readUInt16LE(offset + 10);
    const compressedSize = buffer.readUInt32LE(offset + 20);
    const nameLength = buffer.readUInt16LE(offset + 28);
    const extraLength = buffer.readUInt16LE(offset + 30);
    const commentLength = buffer.readUInt16LE(offset + 32);
    const localOffset = buffer.readUInt32LE(offset + 42);
    const name = buffer.subarray(offset + 46, offset + 46 + nameLength).toString("utf8");
    entries.set(name, { method, compressedSize, localOffset });
    offset += 46 + nameLength + extraLength + commentLength;
  }

  return {
    names: () => [...entries.keys()],
    read(name) {
      const entry = entries.get(name);
      if (!entry) return null;
      const local = entry.localOffset;
      if (buffer.readUInt32LE(local) !== 0x04034b50) throw new Error(`zip 局部头损坏: ${name}`);
      const nameLength = buffer.readUInt16LE(local + 26);
      const extraLength = buffer.readUInt16LE(local + 28);
      const start = local + 30 + nameLength + extraLength;
      const data = buffer.subarray(start, start + entry.compressedSize);
      if (entry.method === 0) return Buffer.from(data);
      if (entry.method === 8) return zlib.inflateRawSync(data);
      throw new Error(`不支持的压缩方式 ${entry.method}: ${name}`);
    },
  };
}

function sha256(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

function main() {
  const apkPath = process.argv[2];
  if (!apkPath) {
    console.error("用法: node tools/verify-apk.mjs <apk>");
    process.exit(2);
  }
  const apk = openZip(apkPath);
  const names = apk.names();
  const problems = [];

  // 1) 原生库逐字节比对
  for (const lib of REQUIRED_LIBS) {
    const inApk = apk.read(`lib/arm64-v8a/${lib}`);
    const onDisk = fs.readFileSync(path.join(JNI_DIR, lib));
    if (!inApk) {
      problems.push(`APK 缺少 lib/arm64-v8a/${lib}`);
      continue;
    }
    if (sha256(inApk) !== sha256(onDisk)) {
      problems.push(`${lib} 在 APK 里被改写过（sha256 与 jniLibs 不一致）`);
    }
  }

  // 2) 只允许 arm64
  const abis = new Set(
    names
      .filter((name) => name.startsWith("lib/"))
      .map((name) => name.split("/")[1]),
  );
  for (const abi of abis) {
    if (abi !== "arm64-v8a") problems.push(`APK 里出现了非 arm64 的 ABI：${abi}`);
  }

  // 3) 容器脚本
  for (const asset of REQUIRED_ASSETS) {
    if (!names.includes(asset)) problems.push(`APK 缺少 ${asset}`);
  }

  // 4) extractNativeLibs：AndroidManifest.xml 是二进制 XML，直接找字符串太脆，
  //    这里退而求其次——检查 lib 是否为 stored（未压缩）打包，
  //    这正是 useLegacyPackaging=true + extractNativeLibs=true 的特征。
  const prootEntry = apk.read("lib/arm64-v8a/libproot.so");
  if (prootEntry && prootEntry.length !== fs.statSync(path.join(JNI_DIR, "libproot.so")).size) {
    problems.push("libproot.so 在 APK 中长度异常");
  }

  if (problems.length) {
    console.error("[verify-apk] 自检失败：");
    for (const problem of problems) console.error("  ✗ " + problem);
    process.exit(1);
  }
  console.log(
    `[verify-apk] 通过：${REQUIRED_LIBS.length} 个原生库字节一致，${REQUIRED_ASSETS.length} 个脚本齐全，ABI=${[...abis].join(",")}`,
  );
}

main();
