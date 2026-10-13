package dev.pocket.opencode.core

/**
 * 镜像目录。
 *
 * 全部地址都在 2026-10 用真实请求验证过（见 tools/mirror-check.mjs 的检查方式）：
 *   - Ubuntu：目录列表里能扫到 `ubuntu-base-<版本>-base-arm64.tar.gz` 且 HEAD 200；
 *   - apt：`dists/noble/Release` 可读；
 *   - Node：`index.json` 可解析、含带 `linux-arm64` 的 LTS 版本且 tarball 200；
 *   - npm：`opencode-ai/latest` 返回版本号，且 `opencode-linux-arm64/-/…tgz` 200。
 * 已验证不可用的源（sjtu 的 ubuntu-cdimage、ustc 的 nodejs-release、tuna 的 npm）没有列进来。
 */
object Mirrors {

    data class Mirror(
        val id: String,
        val name: String,
        /** 基础地址；Ubuntu 源指向“目录”，Node 源指向 index.json 所在目录。 */
        val base: String,
        val region: String = "",
    )

    /** Ubuntu base rootfs（aarch64）。 */
    val ubuntu = listOf(
        Mirror("tuna", "清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release", "国内"),
        Mirror("ustc", "中科大 USTC", "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release", "国内"),
        Mirror("nju", "南京大学", "https://mirror.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release", "国内"),
        Mirror("bfsu", "北外 BFSU", "https://mirrors.bfsu.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release", "国内"),
        Mirror("cernet", "教育网联合", "https://mirrors.cernet.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release", "国内"),
        Mirror("aliyun", "阿里云", "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release", "国内"),
        Mirror("huawei", "华为云", "https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release", "国内"),
        Mirror("official", "Ubuntu 官方", "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release", "海外"),
    )

    /** Ubuntu apt 源（arm64 走 ports）。 */
    val apt = listOf(
        Mirror("tuna", "清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports", "国内"),
        Mirror("ustc", "中科大 USTC", "https://mirrors.ustc.edu.cn/ubuntu-ports", "国内"),
        Mirror("bfsu", "北外 BFSU", "https://mirrors.bfsu.edu.cn/ubuntu-ports", "国内"),
        Mirror("zju", "浙江大学", "https://mirrors.zju.edu.cn/ubuntu-ports", "国内"),
        Mirror("cernet", "教育网联合", "https://mirrors.cernet.edu.cn/ubuntu-ports", "国内"),
        Mirror("aliyun", "阿里云", "https://mirrors.aliyun.com/ubuntu-ports", "国内"),
        Mirror("huawei", "华为云", "https://repo.huaweicloud.com/ubuntu-ports", "国内"),
        Mirror("official", "Ubuntu 官方", "http://ports.ubuntu.com/ubuntu-ports", "海外"),
    )

    /** Node.js 发行版。 */
    val node = listOf(
        Mirror("npmmirror", "npmmirror", "https://npmmirror.com/mirrors/node", "国内"),
        Mirror("tuna", "清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/nodejs-release", "国内"),
        Mirror("tencent", "腾讯云", "https://mirrors.cloud.tencent.com/nodejs-release", "国内"),
        Mirror("bfsu", "北外 BFSU", "https://mirrors.bfsu.edu.cn/nodejs-release", "国内"),
        Mirror("huawei", "华为云", "https://mirrors.huaweicloud.com/nodejs", "国内"),
        Mirror("aliyun", "阿里云", "https://mirrors.aliyun.com/nodejs-release", "国内"),
        Mirror("official", "Node 官方", "https://nodejs.org/dist", "海外"),
    )

    /** npm registry。 */
    val npm = listOf(
        Mirror("npmmirror", "npmmirror", "https://registry.npmmirror.com", "国内"),
        Mirror("tencent", "腾讯云", "https://mirrors.cloud.tencent.com/npm", "国内"),
        Mirror("huawei", "华为云", "https://repo.huaweicloud.com/repository/npm", "国内"),
        Mirror("official", "npm 官方", "https://registry.npmjs.org", "海外"),
    )

    fun find(list: List<Mirror>, id: String?, fallbackIndex: Int = 0): Mirror =
        list.firstOrNull { it.id == id } ?: list.getOrElse(fallbackIndex) { list.first() }
}
