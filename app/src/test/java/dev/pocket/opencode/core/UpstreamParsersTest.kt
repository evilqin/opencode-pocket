package dev.pocket.opencode.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 这些解析对应「上游文件长什么样」，用真实抓下来的样本做回归：
 * Node 的 index.json、Ubuntu 镜像目录列表、SHA256SUMS 文本。
 */
class UpstreamParsersTest {

    // 取自 https://npmmirror.com/mirrors/node/index.json（截取，注意 lts 是代号字符串、files 是平台标识）
    private val nodeIndex = """
    [
      {"version":"v24.21.0","lts":"Krypton","files":["aix-ppc64","headers","linux-arm64","linux-armv7l","linux-x64","osx-arm64-tar","win-arm64-7z"]},
      {"version":"v25.0.0","lts":false,"files":["headers","linux-arm64","linux-x64"]},
      {"version":"v22.16.0","lts":"Jod","files":["headers","linux-arm64","linux-x64","win-x64-zip"]},
      {"version":"v4.2.0","lts":"Argon","files":["headers","linux-arm64","linux-x64"]},
      {"version":"v20.5.0","lts":"Iron","files":["headers","linux-x64"]}
    ]
    """.trimIndent()

    @Test
    fun `挑出最新的 arm64 LTS（按版本号而不是顺序）`() {
        val pick = UpstreamParsers.pickNodeLts(nodeIndex)
        assertNotNull(pick)
        assertEquals("v24.21.0", pick!!.version)
        assertEquals("Krypton", pick.lts)
    }

    @Test
    fun `没有 arm64 的 LTS 会被跳过`() {
        val json = """[{"version":"v20.5.0","lts":"Iron","files":["headers","linux-x64"]}]"""
        assertNull(UpstreamParsers.pickNodeLts(json))
    }

    @Test
    fun `非 LTS 与畸形 JSON 不会崩`() {
        assertNull(UpstreamParsers.pickNodeLts("""[{"version":"v25.0.0","lts":false,"files":["linux-arm64"]}]"""))
        assertNull(UpstreamParsers.pickNodeLts("not json"))
        assertNull(UpstreamParsers.pickNodeLts("[]"))
    }

    // 取自清华 ubuntu-cdimage 的目录列表
    private val ubuntuListing = """
    <a href="ubuntu-base-24.04.3-base-arm64.tar.gz">ubuntu-base-24.04.3-base-arm64.tar.gz</a>
    <a href="ubuntu-base-24.04.3-base-arm64.tar.gz.asc">sig</a>
    <a href="ubuntu-base-24.04.10-base-arm64.tar.gz">ubuntu-base-24.04.10-base-arm64.tar.gz</a>
    <a href="ubuntu-base-24.04.5-base-arm64.tar.gz">ubuntu-base-24.04.5-base-arm64.tar.gz</a>
    <a href="SHA256SUMS">SHA256SUMS</a>
    """.trimIndent()

    @Test
    fun `挑出 Ubuntu 目录里最新的 arm64 rootfs`() {
        // 24.04.10 必须胜过 24.04.5（字符串比较会挑错）
        assertEquals("ubuntu-base-24.04.10-base-arm64.tar.gz", UpstreamParsers.pickUbuntuBase(ubuntuListing))
    }

    @Test
    fun `目录里没有 arm64 文件时返回 null`() {
        assertNull(UpstreamParsers.pickUbuntuBase("<a href=\"ubuntu-base-24.04.5-base-amd64.tar.gz\">x</a>"))
    }

    private val shasums = """
    a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2 *ubuntu-base-24.04.5-base-arm64.tar.gz
    0000000000000000000000000000000000000000000000000000000000000000  ubuntu-base-24.04.4-base-arm64.tar.gz
    # comment line
    not-a-digest  some-file.txt
    """.trimIndent()

    @Test
    fun `从 SHA256SUMS 里取指定文件的摘要`() {
        assertEquals(
            "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
            UpstreamParsers.sha256For("ubuntu-base-24.04.5-base-arm64.tar.gz", shasums),
        )
        assertNull(UpstreamParsers.sha256For("ubuntu-base-24.04.3-base-arm64.tar.gz", shasums))
    }

    @Test
    fun `SHASUMS256 txt 的双空格格式也能解析`() {
        val nodeSums = """
        6ad1325edbdb5649c379b75a237147a666c95d4f9ae8d340fef2d1575d289ad2  node-v24.21.0-linux-arm64.tar.xz
        """.trimIndent() + "\n"
        assertEquals(
            "6ad1325edbdb5649c379b75a237147a666c95d4f9ae8d340fef2d1575d289ad2",
            UpstreamParsers.sha256For("node-v24.21.0-linux-arm64.tar.xz", nodeSums),
        )
    }

    @Test
    fun `带路径前缀的文件名也能匹配`() {
        assertEquals(
            "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
            UpstreamParsers.sha256For(
                "ubuntu-base-24.04.5-base-arm64.tar.gz",
                "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2  ./releases/ubuntu-base-24.04.5-base-arm64.tar.gz",
            ),
        )
    }
}
