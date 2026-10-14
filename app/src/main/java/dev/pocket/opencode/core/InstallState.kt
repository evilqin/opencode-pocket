package dev.pocket.opencode.core

/**
 * 安装状态的合并规则（纯函数，独立成文件是为了能在 JVM 单元测试里锁住行为）。
 *
 * 这些规则踩过的坑：
 *   - 装完之后重启 App 会显示「未安装」：磁盘探测的路径写错了（缺 root/ 前缀）；
 *   - 失败原因被刷新抹掉：refresh() 早先会把 FAILED 记录删掉；
 *   - 正在跑的步骤被刷新打断：refresh() 早先无条件把 running 置空。
 */
object InstallState {

    /**
     * 把「状态文件里的记录」和「磁盘上实际探测的结果」合并成界面用的快照列表。
     *
     * @param records 状态文件里读到的记录（key = step.id）
     * @param disk 磁盘探测结果（true = 确实装好了）
     * @param running 当前正在执行的步骤（不会被刷新打断）
     * @param current 取「当前快照」的回调，用于保留正在执行步骤的进度
     */
    fun merge(
        records: Map<String, StepSnapshot>,
        disk: Map<InstallStep, Boolean>,
        running: InstallStep?,
        current: (InstallStep) -> StepSnapshot,
    ): List<StepSnapshot> = InstallStep.entries.map { step ->
        val record = records[step.id]
        when {
            // 正在执行的步骤不要被刷新打断（否则进度条会停在 0）
            running == step -> current(step)
            // 失败信息优先于磁盘自愈：产物可能存在但自检没过（例如二进制下好了但跑不起来）
            record?.state == StepState.FAILED -> record
            record?.state == StepState.SKIPPED -> record
            // 磁盘上确实验证过：补成 DONE
            disk[step] == true -> StepSnapshot(
                step = step,
                state = StepState.DONE,
                detail = record?.detail.orEmpty(),
                progress = 1f,
            )
            // 上次跑到一半被杀掉/取消：记录里的 RUNNING 是残影，退回待安装
            record?.state == StepState.RUNNING -> StepSnapshot(step, StepState.PENDING, record.detail)
            // 记录说完成但磁盘上没了（例如手动删了 rootfs）：退回待安装
            record?.state == StepState.DONE -> StepSnapshot(step, StepState.PENDING, record.detail)
            record != null -> record
            else -> StepSnapshot(step)
        }
    }

    /** 是否「装好了」：可选步骤（Node）跳过也算通过。 */
    fun isInstalled(steps: List<StepSnapshot>, installNode: Boolean): Boolean =
        steps
            .filter { !it.step.optional || (it.step == InstallStep.NODE && installNode) }
            .all { it.state == StepState.DONE || (it.step == InstallStep.NODE && it.state == StepState.SKIPPED) }
}
