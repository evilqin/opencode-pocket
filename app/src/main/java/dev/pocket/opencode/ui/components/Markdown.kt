package dev.pocket.opencode.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocket.opencode.ui.PocketIcons
import dev.pocket.opencode.ui.theme.CodeTextStyle
import dev.pocket.opencode.ui.theme.PocketColors

fun copyToClipboard(context: Context, text: String, label: String = "已复制") {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("opencode", text))
    Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Code -> CodeBlock(block.language, block.text)

                is MdBlock.Heading -> Text(
                    block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                )

                is MdBlock.Paragraph -> Text(
                    MarkdownParser.inline(block.text, scheme.primary, scheme.surfaceContainerHighest, scheme.primary),
                    style = bodyStyle,
                )

                is MdBlock.Bullet -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    block.items.forEach { item ->
                        Row {
                            Text("•", style = bodyStyle, color = scheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                MarkdownParser.inline(item, scheme.primary, scheme.surfaceContainerHighest, scheme.primary),
                                style = bodyStyle,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(scheme.primary.copy(alpha = 0.5f)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        MarkdownParser.inline(block.text, scheme.primary, scheme.surfaceContainerHighest, scheme.primary),
                        style = bodyStyle.copy(color = scheme.onSurfaceVariant),
                    )
                }
            }
        }
    }
}

@Composable
fun CodeBlock(language: String, code: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF060B14))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language.ifBlank { "code" },
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF7C8BA3),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    copyToClipboard(context, code, "代码已复制")
                    copied = true
                },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    if (copied) Icons.Filled.Check else PocketIcons.Copy,
                    contentDescription = "复制",
                    modifier = Modifier.size(15.dp),
                    tint = if (copied) PocketColors.Emerald else Color(0xFF8FA0BC),
                )
            }
        }
        Text(
            code,
            style = CodeTextStyle,
            color = Color(0xFFD7E3F4),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}
