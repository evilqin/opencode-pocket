package dev.pocket.opencode.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocket.opencode.data.FileDiffInfo
import dev.pocket.opencode.data.UnifiedDiff
import dev.pocket.opencode.ui.components.EmptyState
import dev.pocket.opencode.ui.components.copyToClipboard
import dev.pocket.opencode.ui.theme.CodeTextStyle
import dev.pocket.opencode.ui.theme.PocketColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
/**
 * 「本次改动」视图：列出会话里被 agent 改过的文件，点进去看统一差分。
 *
 * 数据来自服务端 GET /session/:id/diff（可带 messageID 只看某一轮），
 * 差分本身由 [UnifiedDiff] 本地计算（纯逻辑、有单测），失败时只显示统计。
 */
@Composable
fun DiffPanel(
    diffs: List<FileDiffInfo>,
    loading: Boolean,
    error: String?,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<FileDiffInfo?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            val current = selected
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (current != null) selected = null else onDismiss() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, if (current != null) "返回列表" else "关闭")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            current?.shortName ?: "本次改动",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = when {
                            current != null -> "+${current.additions} -${current.deletions} · ${current.file}"
                            diffs.isNotEmpty() -> "${diffs.size} 个文件 · +${diffs.sumOf { it.additions }} -${diffs.sumOf { it.deletions }}"
                            else -> "agent 还没有改动过文件"
                        }
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (current == null) {
                        IconButton(onClick = onReload) { Icon(Icons.Filled.Refresh, "刷新") }
                    }
                }
            }

            Box(Modifier.weight(1f)) {
                when {
                    loading && diffs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }

                    error != null && diffs.isEmpty() -> EmptyState(
                        icon = PocketIcons.Shield,
                        title = "拿不到改动列表",
                        body = error,
                    )

                    diffs.isEmpty() -> EmptyState(
                        icon = PocketIcons.Code,
                        title = "还没有文件改动",
                        body = "让 opencode 写点代码，改过的文件会出现在这里（支持查看每一行的增删）",
                    )

                    current != null -> DiffDetail(current)

                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(diffs, key = { it.file }) { diff ->
                            FileDiffRow(diff) { selected = diff }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDiffRow(diff: FileDiffInfo, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        onClick = onClick,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    diff.shortName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    diff.file,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (diff.isNew) StatusTag("新增", PocketColors.Emerald)
            if (diff.isDeleted) StatusTag("删除", PocketColors.Rose)
            Text(
                "+${diff.additions}",
                style = MaterialTheme.typography.labelMedium,
                color = PocketColors.Emerald,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "-${diff.deletions}",
                style = MaterialTheme.typography.labelMedium,
                color = PocketColors.Rose,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun DiffDetail(diff: FileDiffInfo) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 差分计算可能涉及大文本，放到 Default 线程，避免阻塞主线程
    val lines by produceState<List<UnifiedDiff.Line>?>(null, diff.file, diff.before, diff.after) {
        value = withContext(Dispatchers.Default) {
            UnifiedDiff.compute(diff.before, diff.after)
        }
    }
    // produceState 的委托属性不能被智能转换：先拷到局部变量再判空
    val computed = lines

    if (computed == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
        return
    }

    if (computed.isEmpty()) {
        // 服务端可能只给了统计（before/after 被省略）
        EmptyState(
            icon = PocketIcons.Code,
            title = if (diff.additions + diff.deletions > 0) "只能看到统计" else "内容没有变化",
            body = if (diff.additions + diff.deletions > 0) {
                "服务端没有返回文件内容，仅 +${diff.additions} -${diff.deletions}；可以用终端里的 git diff 查看"
            } else {
                "这个文件的 before/after 完全一致"
            },
        )
        return
    }

    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
            itemsIndexed(computed) { index, line ->
                val background = when (line) {
                    is UnifiedDiff.Line.Add -> PocketColors.Emerald.copy(alpha = 0.12f)
                    is UnifiedDiff.Line.Remove -> PocketColors.Rose.copy(alpha = 0.12f)
                    is UnifiedDiff.Line.Hunk -> MaterialTheme.colorScheme.surfaceContainerHigh
                    is UnifiedDiff.Line.Note -> MaterialTheme.colorScheme.surfaceContainer
                    is UnifiedDiff.Line.Context -> Color.Transparent
                }
                val tint = when (line) {
                    is UnifiedDiff.Line.Add -> PocketColors.Emerald
                    is UnifiedDiff.Line.Remove -> PocketColors.Rose
                    is UnifiedDiff.Line.Hunk -> MaterialTheme.colorScheme.primary
                    is UnifiedDiff.Line.Note -> MaterialTheme.colorScheme.onSurfaceVariant
                    is UnifiedDiff.Line.Context -> MaterialTheme.colorScheme.onSurface
                }
                val text = when (line) {
                    is UnifiedDiff.Line.Hunk -> line.text
                    is UnifiedDiff.Line.Context -> " ${line.text}"
                    is UnifiedDiff.Line.Add -> "+${line.text}"
                    is UnifiedDiff.Line.Remove -> "-${line.text}"
                    is UnifiedDiff.Line.Note -> line.text
                }
                Surface(color = background) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
                        Text(
                            text.ifEmpty { " " },
                            style = CodeTextStyle,
                            color = tint,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp),
                        )
                    }
                }
                if (index == computed.lastIndex) Spacer(Modifier.height(4.dp))
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { copyToClipboard(context, UnifiedDiff.render(computed), "差分已复制") }) {
                    Text("复制差分")
                }
                TextButton(onClick = { copyToClipboard(context, diff.after, "新文件内容已复制") }) {
                    Text("复制新内容")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "+${diff.additions} -${diff.deletions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
