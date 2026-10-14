package dev.pocket.opencode.core

/**
 * 容器里的 CA 证书 bundle（`/etc/ssl/certs/ca-certificates.crt`）。
 *
 * 为什么单独为一件事写一个对象：Ubuntu base 镜像里**没有** ca-certificates，而我们的 apt 源是 https。
 * 没有 CA 时 `apt-get update` 会以 "No system certificates available" 全量失败，但**仍然返回 0**
 * （只给 W: 警告），于是下一步 `apt-get install` 才报 "Unable to locate package" —— 看起来
 * 完全不像证书问题（这一点是 CI 容器冒烟测试抓出来的）。
 *
 * App 的应对：从 Android 的系统证书库（`/system/etc/security/cacerts`，每张证书一个 PEM 文件）
 * 拼成一份 bundle 写进 rootfs；装了 ca-certificates 之后再交给 `update-ca-certificates` 管。
 */
object CaBundle {

    /** 小于这个大小视为不可用（空文件 / 只有占位内容）。 */
    const val MIN_BYTES = 8 * 1024L

    /** 相对 rootfs 的路径。 */
    const val GUEST_PATH = "etc/ssl/certs/ca-certificates.crt"

    /** 是否需要（重新）注入。 */
    fun needsInjection(exists: Boolean, size: Long): Boolean = !exists || size <= MIN_BYTES
}
