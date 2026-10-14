package dev.pocket.opencode.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁住安装状态的合并规则（这几个规则都真实出过 bug）。 */
class InstallStateTest {

    private fun record(step: InstallStep, state: StepState, detail: String = "", error: String? = null) =
        StepSnapshot(step, state, detail, progress = if (state == StepState.DONE) 1f else -1f, error = error)

    private val allDone = InstallStep.entries.associateWith { true }

    @Test
    fun `磁盘上有的步骤一律补成 DONE`() {
        val merged = InstallState.merge(
            records = mapOf("rootfs" to record(InstallStep.ROOTFS, StepState.PENDING)),
            disk = mapOf(InstallStep.ROOTFS to true),
            running = null,
            current = { it.let { step -> StepSnapshot(step) } },
        )
        assertEquals(StepState.DONE, merged.first { it.step == InstallStep.ROOTFS }.state)
    }

    @Test
    fun `失败的步骤与错误原因不会被刷新抹掉`() {
        val failed = record(InstallStep.TOOLS, StepState.FAILED, error = "apt update 失败：Temporary failure resolving")
        val merged = InstallState.merge(
            records = mapOf("tools" to failed),
            disk = mapOf(InstallStep.TOOLS to false),
            running = null,
            current = { StepSnapshot(it) },
        )
        val step = merged.first { it.step == InstallStep.TOOLS }
        assertEquals(StepState.FAILED, step.state)
        assertTrue(step.error!!.contains("Temporary failure"))
    }

    @Test
    fun `正在跑的步骤保持进度而不是被清掉`() {
        val running = InstallStep.NODE
        val inProgress = StepSnapshot(running, StepState.RUNNING, "下载 Node", progress = 0.42f)
        val merged = InstallState.merge(
            records = emptyMap(),
            disk = mapOf(InstallStep.NODE to false),
            running = running,
            current = { if (it == running) inProgress else StepSnapshot(it) },
        )
        val step = merged.first { it.step == running }
        assertEquals(StepState.RUNNING, step.state)
        assertEquals(0.42f, step.progress, 0.0001f)
    }

    @Test
    fun `记录说完成但磁盘上没了就退回待安装`() {
        val merged = InstallState.merge(
            records = mapOf("rootfs" to record(InstallStep.ROOTFS, StepState.DONE, "Ubuntu 24.04.5")),
            disk = mapOf(InstallStep.ROOTFS to false),
            running = null,
            current = { StepSnapshot(it) },
        )
        val step = merged.first { it.step == InstallStep.ROOTFS }
        assertEquals(StepState.PENDING, step.state)
        // 详情保留，方便用户知道上次装到哪个版本
        assertEquals("Ubuntu 24.04.5", step.detail)
    }

    @Test
    fun `跳过的可选步骤保持跳过`() {
        val merged = InstallState.merge(
            records = mapOf("node" to record(InstallStep.NODE, StepState.SKIPPED, "已跳过")),
            disk = mapOf(InstallStep.NODE to false),
            running = null,
            current = { StepSnapshot(it) },
        )
        assertEquals(StepState.SKIPPED, merged.first { it.step == InstallStep.NODE }.state)
    }

    @Test
    fun `没有记录就是待安装`() {
        val merged = InstallState.merge(emptyMap(), emptyMap(), null) { StepSnapshot(it) }
        assertEquals(InstallStep.entries.size, merged.size)
        assertTrue(merged.all { it.state == StepState.PENDING })
    }

    @Test
    fun `全部完成才算装好`() {
        val steps = InstallStep.entries.map { record(it, StepState.DONE) }
        assertTrue(InstallState.isInstalled(steps, installNode = true))
        assertTrue(InstallState.isInstalled(steps, installNode = false))
    }

    @Test
    fun `可选步骤跳过不影响装好判定`() {
        val steps = InstallStep.entries.map {
            if (it == InstallStep.NODE) record(it, StepState.SKIPPED) else record(it, StepState.DONE)
        }
        // 用户选了装 Node 但跳过 -> 仍算装好（跳过是用户明确选择）
        assertTrue(InstallState.isInstalled(steps, installNode = true))
    }

    @Test
    fun `还有待安装步骤就不算装好`() {
        val steps = InstallStep.entries.map {
            if (it == InstallStep.CONFIG) record(it, StepState.PENDING) else record(it, StepState.DONE)
        }
        assertFalse(InstallState.isInstalled(steps, installNode = true))
    }

    @Test
    fun `失败步骤不算装好`() {
        val steps = InstallStep.entries.map {
            if (it == InstallStep.OPENCODE) record(it, StepState.FAILED) else record(it, StepState.DONE)
        }
        assertFalse(InstallState.isInstalled(steps, installNode = true))
    }

    @Test
    fun `全绿场景：混合状态也能正确合并`() {
        val merged = InstallState.merge(
            records = mapOf(
                "rootfs" to record(InstallStep.ROOTFS, StepState.DONE, "Ubuntu 24.04.5"),
                "tools" to record(InstallStep.TOOLS, StepState.DONE),
                "node" to record(InstallStep.NODE, StepState.SKIPPED),
                "opencode" to record(InstallStep.OPENCODE, StepState.FAILED, error = "校验和不匹配"),
            ),
            disk = mapOf(
                InstallStep.ROOTFS to true,
                InstallStep.TOOLS to true,
                InstallStep.NODE to false,
                InstallStep.OPENCODE to false,
            ),
            running = null,
            current = { StepSnapshot(it) },
        )
        assertEquals(StepState.DONE, merged.first { it.step == InstallStep.ROOTFS }.state)
        assertEquals(StepState.DONE, merged.first { it.step == InstallStep.TOOLS }.state)
        assertEquals(StepState.SKIPPED, merged.first { it.step == InstallStep.NODE }.state)
        assertEquals(StepState.FAILED, merged.first { it.step == InstallStep.OPENCODE }.state)
        assertEquals(StepState.PENDING, merged.first { it.step == InstallStep.CONFIG }.state)
        assertFalse(InstallState.isInstalled(merged, installNode = true))
    }

    @Test
    fun `allDone 里的每个步骤都能被磁盘探测覆盖`() {
        val merged = InstallState.merge(emptyMap(), allDone, null) { StepSnapshot(it) }
        assertTrue(merged.all { it.state == StepState.DONE })
        assertTrue(InstallState.isInstalled(merged, installNode = true))
    }
}
