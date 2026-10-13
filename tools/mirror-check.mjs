// 一次性镜像体检脚本（不进仓库，只用来核对 Mirrors.kt 里的地址是否真实可用）
const UA = { "User-Agent": "OpenCodePocket/1.0 (Android)" };

const ubuntu = [
  ["tuna", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release"],
  ["ustc", "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release"],
  ["nju", "https://mirror.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release"],
  ["bfsu", "https://mirrors.bfsu.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release"],
  ["sjtu", "https://mirror.sjtu.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release"],
  ["aliyun", "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release"],
  ["huawei", "https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release"],
  ["official", "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"],
];

const apt = [
  ["tuna", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"],
  ["ustc", "https://mirrors.ustc.edu.cn/ubuntu-ports"],
  ["aliyun", "https://mirrors.aliyun.com/ubuntu-ports"],
  ["huawei", "https://repo.huaweicloud.com/ubuntu-ports"],
  ["nju", "https://mirror.nju.edu.cn/ubuntu-ports"],
  ["official", "http://ports.ubuntu.com/ubuntu-ports"],
];

const node = [
  ["npmmirror", "https://npmmirror.com/mirrors/node"],
  ["tuna", "https://mirrors.tuna.tsinghua.edu.cn/nodejs-release"],
  ["huawei", "https://mirrors.huaweicloud.com/nodejs"],
  ["aliyun", "https://mirrors.aliyun.com/nodejs-release"],
  ["ustc", "https://mirrors.ustc.edu.cn/nodejs-release"],
  ["official", "https://nodejs.org/dist"],
];

const npm = [
  ["npmmirror", "https://registry.npmmirror.com"],
  ["tuna", "https://mirrors.tuna.tsinghua.edu.cn/npm"],
  ["tencent", "https://mirrors.cloud.tencent.com/npm"],
  ["huawei", "https://repo.huaweicloud.com/repository/npm"],
  ["official", "https://registry.npmjs.org"],
];

async function head(url) {
  try {
    const r = await fetch(url, { method: "HEAD", headers: UA, redirect: "follow" });
    return { ok: r.ok, status: r.status, len: r.headers.get("content-length") };
  } catch (e) {
    return { ok: false, status: 0, err: e.message };
  }
}

async function get(url) {
  try {
    const r = await fetch(url, { headers: UA, redirect: "follow" });
    const text = r.ok ? await r.text() : "";
    return { ok: r.ok, status: r.status, text };
  } catch (e) {
    return { ok: false, status: 0, text: "", err: e.message };
  }
}

const results = [];
function record(group, id, ok, detail) {
  results.push({ group, id, ok, detail });
  console.log(`${ok ? "OK  " : "FAIL"} [${group}] ${id}: ${detail}`);
}

async function checkUbuntu() {
  await Promise.all(
    ubuntu.map(async ([id, base]) => {
      const listing = await get(base + "/");
      if (!listing.ok) return record("ubuntu", id, false, `目录列表 HTTP ${listing.status || listing.err}`);
      const m = [...listing.text.matchAll(/ubuntu-base-(\d+\.\d+\.\d+)-base-arm64\.tar\.gz/g)].map((x) => x[0]);
      if (!m.length) return record("ubuntu", id, false, "目录里没有 arm64 rootfs");
      const file = m[m.length - 1];
      const h = await head(`${base}/${file}`);
      record("ubuntu", id, h.ok, `${file} → HTTP ${h.status || h.err}${h.len ? ` (${(h.len / 1024 / 1024).toFixed(1)} MB)` : ""}`);
    }),
  );
}

async function checkApt() {
  await Promise.all(
    apt.map(async ([id, base]) => {
      const r = await get(`${base}/dists/noble/Release`);
      record("apt", id, r.ok, r.ok ? "noble/Release 可读" : `HTTP ${r.status || r.err}`);
    }),
  );
}

async function checkNode() {
  await Promise.all(
    node.map(async ([id, base]) => {
      const r = await get(`${base}/index.json`);
      if (!r.ok) return record("node", id, false, `index.json HTTP ${r.status || r.err}`);
      let best = null;
      try {
        for (const e of JSON.parse(r.text)) {
          if (!e.lts || e.lts === false) continue;
          if (!(e.files || []).some((f) => f.includes("linux-arm64.tar.xz"))) continue;
          if (!best || e.version.localeCompare(best.version, undefined, { numeric: true }) > 0) best = e;
        }
      } catch (e) {
        return record("node", id, false, `index.json 解析失败 ${e.message}`);
      }
      if (!best) return record("node", id, false, "没有 arm64 的 LTS 版本");
      const h = await head(`${base}/${best.version}/node-${best.version}-linux-arm64.tar.xz`);
      record("node", id, h.ok, `${best.version} → HTTP ${h.status || h.err}${h.len ? ` (${(h.len / 1024 / 1024).toFixed(1)} MB)` : ""}`);
    }),
  );
}

async function checkNpm() {
  await Promise.all(
    npm.map(async ([id, base]) => {
      const meta = await get(`${base}/opencode-ai/latest`);
      if (!meta.ok) return record("npm", id, false, `opencode-ai/latest HTTP ${meta.status || meta.err}`);
      let version = "";
      try {
        version = JSON.parse(meta.text).version || "";
      } catch (e) {
        return record("npm", id, false, `元数据不是 JSON：${meta.text.slice(0, 60)}`);
      }
      const url = `${base}/opencode-linux-arm64/-/opencode-linux-arm64-${version}.tgz`;
      const h = await head(url);
      record("npm", id, h.ok, `v${version} tarball → HTTP ${h.status || h.err}${h.len ? ` (${(h.len / 1024 / 1024).toFixed(1)} MB)` : ""}`);
    }),
  );
}

await checkUbuntu();
await checkApt();
await checkNode();
await checkNpm();

const failed = results.filter((r) => !r.ok);
console.log(`\n== 汇总：${results.length - failed.length}/${results.length} 可用 ==`);
for (const f of failed) console.log(`  不可用：[${f.group}] ${f.id} — ${f.detail}`);
