package dev.pocket.opencode.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CA bundle 判定逻辑。
 *
 * 背景（CI 容器冒烟跑出来的真问题）：Ubuntu base 里没有 ca-certificates，
 * apt 走 https 会全量失败但 apt-get update 仍返回 0，导致后面 install 报
 * "Unable to locate package"，完全看不出是证书问题。
 */
class CaBundleTest {

    @Test
    fun `文件不存在时需要注入`() {
        assertTrue(CaBundle.needsInjection(exists = false, size = 0))
        assertTrue(CaBundle.needsInjection(exists = false, size = 999_999))
    }

    @Test
    fun `空文件或过小的文件都需要重新注入`() {
        assertTrue(CaBundle.needsInjection(exists = true, size = 0))
        assertTrue(CaBundle.needsInjection(exists = true, size = 1))
        assertTrue(CaBundle.needsInjection(exists = true, size = CaBundle.MIN_BYTES))
    }

    @Test
    fun `正常大小的 bundle 不需要动`() {
        assertFalse(CaBundle.needsInjection(exists = true, size = CaBundle.MIN_BYTES + 1))
        assertFalse(CaBundle.needsInjection(exists = true, size = 200 * 1024))
    }

    @Test
    fun `guest 路径与 apt 默认查找的位置一致`() {
        assertTrue(CaBundle.GUEST_PATH.endsWith("etc/ssl/certs/ca-certificates.crt"))
    }
}
