package dev.pocket.opencode.core

/**
 * 镜像目录。全部是可直连的 HTTPS 源，按“国内可访问性+速度”排序，用户也可以测速后手动挑。
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
        Mirror("sjtu", "上海交大", "https://mirror.sjtu.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release", "国内"),
        Mirror("aliyun", "阿里云", "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release", "国内"),
        Mirror("huawei", "华为云", "https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release", "国内"),
        Mirror("official", "Ubuntu 官方", "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release", "海外"),
    )

    /** Ubuntu apt 源（arm64 走 ports）。 */
    val apt = listOf(
        Mirror("tuna", "清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports", "国内"),
        Mirror("ustc", "中科大 USTC", "https://mirrors.ustc.edu.cn/ubuntu-ports", "国内"),
        Mirror("aliyun", "阿里云", "https://mirrors.aliyun.com/ubuntu-ports", "国内"),
        Mirror("huawei", "华为云", "https://repo.huaweicloud.com/ubuntu-ports", "国内"),
        Mirror("nju", "南京大学", "https://mirror.nju.edu.cn/ubuntu-ports", "国内"),
        Mirror("official", "Ubuntu 官方", "http://ports.ubuntu.com/ubuntu-ports", "海外"),
    )

    /** Node.js 发行版。 */
    val node = listOf(
        Mirror("npmmirror", "npmmirror", "https://npmmirror.com/mirrors/node", "国内"),
        Mirror("tuna", "清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/nodejs-release", "国内"),
        Mirror("huawei", "华为云", "https://mirrors.huaweicloud.com/nodejs", "国内"),
        Mirror("aliyun", "阿里云", "https://mirrors.aliyun.com/nodejs-release", "国内"),
        Mirror("ustc", "中科大 USTC", "https://mirrors.ustc.edu.cn/nodejs-release", "国内"),
        Mirror("official", "Node 官方", "https://nodejs.org/dist", "海外"),
    )

    /** npm registry。 */
    val npm = listOf(
        Mirror("npmmirror", "npmmirror", "https://registry.npmmirror.com", "国内"),
        Mirror("tuna", "清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/npm", "国内"),
        Mirror("tencent", "腾讯云", "https://mirrors.cloud.tencent.com/npm", "国内"),
        Mirror("huawei", "华为云", "https://repo.huaweicloud.com/repository/npm", "国内"),
        Mirror("official", "npm 官方", "https://registry.npmjs.org", "海外"),
    )

    fun find(list: List<Mirror>, id: String?, fallbackIndex: Int = 0): Mirror =
        list.firstOrNull { it.id == id } ?: list.getOrElse(fallbackIndex) { list.first() }
}
