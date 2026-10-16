package dev.pocket.opencode.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandGuardTest {

    @Test
    fun `正常的只读命令放行`() {
        listOf(
            "getprop ro.product.model",
            "dumpsys battery | head -20",
            "ls -al /sdcard/Download",
            "pm list packages | grep 星",
            "ip addr show wlan0",
            "cat /proc/meminfo",
            "id -u",
        ).forEach { command ->
            assertNull("不该拦截：$command", CommandGuard.check(command))
        }
    }

    @Test
    fun `破坏性命令会被拦下`() {
        listOf(
            "rm -rf /",
            "rm -rf /*",
            "rm -rf /data",
            "rm -rf /sdcard/DCIM",
            "mkfs.ext4 /dev/block/mmcblk0p1",
            "dd if=/dev/zero of=/dev/block/by-name/system",
            "pm uninstall com.tencent.mm",
            "pm clear com.android.providers.contacts",
            "settings put global airplane_mode_on 1",
            "content delete --uri content://contacts/all",
            "reboot",
            "mount -o remount,rw /system",
            "chmod 777 /system/bin",
            "su -c 'id'",
            "am force-stop com.android.systemui",
            "echo x > /system/build.prop",
        ).forEach { command ->
            assertNotNull("应该拦截：$command", CommandGuard.check(command))
        }
    }

    @Test
    fun `关闭幻影进程限制的命令在白名单里`() {
        assertNull(
            CommandGuard.check("settings put global settings_enable_monitor_phantom_procs false"),
        )
    }

    @Test
    fun `换行与回车拼接的命令也能识别`() {
        assertNotNull(CommandGuard.check("echo hi\rrm -rf /"))
        assertNotNull(CommandGuard.check("ls\npm uninstall com.android.chrome"))
    }

    @Test
    fun `规则数量是个正数（UI 会展示）`() {
        assertTrue(CommandGuard.ruleCount > 10)
    }

    @Test
    fun `rm 普通文件不被误拦`() {
        assertNull(CommandGuard.check("rm -f /data/local/tmp/foo.txt"))
        assertNull(CommandGuard.check("rm -rf /sdcard/Download/tmp-build"))
    }

    @Test
    fun `killall 与危险 kill 被拦`() {
        assertNotNull(CommandGuard.check("killall -9 system_server"))
        assertNotNull(CommandGuard.check("kill -9 1"))
        assertEquals(null, CommandGuard.check("kill -9 12345"))
    }

    @Test
    fun `绕写变体也会被规范化后拦下`() {
        listOf(
            "rm  --no-preserve-root -rf /",
            "rm -rf //",
            "rm '' -rf /",
            "rm -rf \${IFS}/",
            "r\\m -rf /",
            "rm -rf /data//local/../..",
        ).forEach { command ->
            assertNotNull("应该拦截：$command", CommandGuard.check(command))
        }
    }

    @Test
    fun `白名单只豁免自己那一段，不会包庇后面的危险命令`() {
        assertNotNull(
            CommandGuard.check(
                "settings put global settings_enable_monitor_phantom_procs false; pm uninstall com.victim",
            ),
        )
        assertNull(CommandGuard.check("settings put global settings_enable_monitor_phantom_procs false"))
    }

    @Test
    fun `结构性构造（藏命令的写法）一律拦截`() {
        listOf(
            "sh -c 'pm list packages'",
            "base64 -d <<< cG0= | sh",
            "eval \$CMD",
            "echo \$(cat /proc/version)",
            "echo `id`",
            "find /sdcard -delete",
            "find /sdcard -name x -exec rm {} ;",
            "ls | xargs rm -rf",
            "a=pm; \$a uninstall com.android.chrome",
            "/system/bin/sh -c id",
        ).forEach { command ->
            assertNotNull("应该拦截：$command", CommandGuard.check(command))
        }
    }

    @Test
    fun `被拦时的原因可以读`() {
        val reason = CommandGuard.check("pm uninstall com.android.chrome")
        assertNotNull(reason)
        assertTrue(reason!!.contains("应用"))
    }

    @Test
    fun `空命令安全返回`() {
        assertNull(CommandGuard.check(""))
        assertNull(CommandGuard.check("   "))
        assertFalse(CommandGuard.ruleCount == 0)
    }
}
