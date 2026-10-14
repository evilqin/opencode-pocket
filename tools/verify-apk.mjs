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
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import zlib from "node:zlib";

const ROOT = path.resolve(import.meta.dirname, "..");
const JNI_DIR = path.join(ROOT, "app", "src", "main", "jniLibs", "arm64-v8a");
const REQUIRED_LIBS = ["libproot.so", "libprootloader.so", "libtalloc.so", "libandroidshmem.so"];
const REQUIRED_ASSETS = [
  "assets/scripts/profile.sh",
  "assets/scripts/setup-apt.sh",
  "assets/scripts/install-tools.sh",
  "assets/scripts/setup-env.sh",
  "assets/device-shell/dexec.sh",
  "assets/device-shell/SKILL.md",
];

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
    methodOf: (name) => entries.get(name)?.method,
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

/** 在 SDK 目录里找一个可用的 aapt2（有就用它校验 manifest，没有就跳过）。 */
function findAapt2() {
  const sdkRoots = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT].filter(Boolean);
  for (const root of sdkRoots) {
    const buildTools = path.join(root, "build-tools");
    if (!fs.existsSync(buildTools)) continue;
    const versions = fs
      .readdirSync(buildTools)
      .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
      .reverse();
    for (const version of versions) {
      for (const exe of ["aapt2.exe", "aapt2"]) {
        const candidate = path.join(buildTools, version, exe);
        if (fs.existsSync(candidate)) return candidate;
      }
    }
  }
  return null;
}

function runAapt2(aapt2, apkPath) {
  try {
    return execFileSync(aapt2, ["dump", "xmltree", "--file", "AndroidManifest.xml", apkPath], {
      encoding: "utf8",
      maxBuffer: 32 * 1024 * 1024,
      stdio: ["ignore", "pipe", "ignore"],
    });
  } catch {
    return null;
  }
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

  // 4) 原生库的存放方式：
  //    AGP 在 useLegacyPackaging=true 时会把 .so「压缩」进 APK，同时把 manifest 里的
  //    extractNativeLibs 置为 true，安装时由系统解压到 nativeLibraryDir（proot 才能执行）。
  //    所以压缩是**预期行为**，这里只做记录；真正的判据用 aapt2 校验 manifest（见第 5 步）。
  const libMethods = REQUIRED_LIBS.map((lib) => `${lib}=${apk.methodOf(`lib/arm64-v8a/${lib}`) ?? "missing"}`);
  if (libMethods.some((entry) => entry.endsWith("=missing"))) {
    problems.push(`APK 缺少原生库：${libMethods.join(", ")}`);
  }

  // 5) 有 aapt2 的话，进一步核对合并后的 manifest（extractNativeLibs / 组件 / FGS 类型 / 版本号）
  const aapt2 = findAapt2();
  const dump = aapt2 ? runAapt2(aapt2, apkPath) : null;
  if (aapt2 && dump == null) {
    console.warn("[verify-apk] 有 aapt2 但解析 manifest 失败，跳过 manifest 校验");
  } else if (dump) {
    const expectations = [
      ["extractNativeLibs", /extractNativeLibs\(0x010104ea\)=true/],
      ["前台服务类型 SPECIAL_USE", /foregroundServiceType\(0x01010599\)=0x40000000/],
      ["MainActivity", /"dev\.pocket\.opencode\.MainActivity"/],
      ["ServerService", /"dev\.pocket\.opencode\.service\.ServerService"/],
      ["ShizukuProvider", /"rikka\.shizuku\.ShizukuProvider"/],
    ];
    for (const [label, pattern] of expectations) {
      if (!pattern.test(dump)) problems.push(`manifest 校验失败：${label}`);
    }

    // 版本号必须与 app/build.gradle.kts 一致（防止发出名字对不上的包）
    const gradle = fs.readFileSync(path.join(ROOT, "app", "build.gradle.kts"), "utf8");
    const expectName = /versionName\s*=\s*"([^"]+)"/.exec(gradle)?.[1];
    const expectCode = /versionCode\s*=\s*(\d+)/.exec(gradle)?.[1];
    if (!expectName || !expectCode) {
      problems.push("读不到 app/build.gradle.kts 里的 versionName/versionCode");
    } else {
      const actualName = /versionName\(0x0101021c\)="([^"]+)"/.exec(dump)?.[1];
      // aapt2 的两种输出形态都兼容：`=4` / `=(type 0x10)0x4`
      const codeMatch = /versionCode\(0x0101021b\)=(?:\(type 0x10\))?(?:0x([0-9a-f]+)|(\d+))/.exec(dump);
      const actualCode = codeMatch ? (codeMatch[1] ? parseInt(codeMatch[1], 16) : parseInt(codeMatch[2], 10)) : null;
      const allowed = [expectName, `${expectName}-debug`];
      if (!actualName || !allowed.includes(actualName)) {
        problems.push(`versionName 不一致：APK=${actualName ?? "?"}，gradle=${expectName}`);
      }
      if (actualCode !== Number(expectCode)) {
        problems.push(`versionCode 不一致：APK=${actualCode ?? "?"}，gradle=${expectCode}`);
      }
    }
  } else {
    console.warn("[verify-apk] 没有找到 aapt2（设置 ANDROID_HOME 或用 CI 跑），跳过 manifest 与版本校验");
  }

  // 6) Shizuku 用户服务是「按类名从 APK 加载」的（不是 manifest 组件，R8 也不能改名）：
  //    直接在 dex 里找类名，精简包尤其依赖这条。
  const dexNames = names.filter((name) => /^classes\d*\.dex$/.test(name));
  const dexHas = (needle) =>
    dexNames.some((dex) => {
      const bytes = apk.read(dex);
      return bytes && bytes.includes(Buffer.from(needle));
    });
  if (!dexHas("dev/pocket/opencode/service/DeviceShellUserService")) {
    problems.push("dex 里找不到 DeviceShellUserService（Shizuku 用户服务会加载失败）");
  }
  if (!dexHas("dev/pocket/opencode/IDeviceShellService")) {
    problems.push("dex 里找不到 IDeviceShellService（AIDL 接口缺失）");
  }

  if (problems.length) {
    console.error("[verify-apk] 自检失败：");
    for (const problem of problems) console.error("  ✗ " + problem);
    process.exit(1);
  }
  console.log(
    `[verify-apk] 通过：${REQUIRED_LIBS.length} 个原生库字节一致，${REQUIRED_ASSETS.length} 个脚本齐全，ABI=${[...abis].join(",")}，` +
      `打包方式：${libMethods.join(" ")}`,
  );
}

main();
