package dev.pocket.opencode.util

import dev.pocket.opencode.i18n.I18n
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Format {

    fun bytes(value: Long): String {
        if (value <= 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var index = 0
        while (size >= 1024 && index < units.lastIndex) {
            size /= 1024
            index++
        }
        return if (index == 0) "${value} B" else String.format(Locale.US, "%.1f %s", size, units[index])
    }

    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "—"
        return bytes(bytesPerSecond) + "/s"
    }

    fun duration(millis: Long, english: Boolean = I18n.isEnglish): String {
        if (millis <= 0) return "0s"
        val days = TimeUnit.MILLISECONDS.toDays(millis)
        val hours = TimeUnit.MILLISECONDS.toHours(millis) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return if (english) {
            when {
                days > 0 -> "${days}d ${hours}h"
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        } else {
            when {
                days > 0 -> "${days}天${hours}小时"
                hours > 0 -> "${hours}小时${minutes}分"
                minutes > 0 -> "${minutes}分${seconds}秒"
                else -> "${seconds}秒"
            }
        }
    }

    fun relativeTime(timestamp: Long, english: Boolean = I18n.isEnglish): String {
        if (timestamp <= 0) return ""
        val diff = System.currentTimeMillis() - timestamp
        return if (english) {
            when {
                diff < 60_000 -> "just now"
                diff < 3_600_000 -> "${diff / 60_000} min ago"
                diff < 86_400_000 -> "${diff / 3_600_000} h ago"
                diff < 7 * 86_400_000L -> "${diff / 86_400_000} d ago"
                else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
            }
        } else {
            when {
                diff < 60_000 -> "刚刚"
                diff < 3_600_000 -> "${diff / 60_000} 分钟前"
                diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
                diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
                else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
            }
        }
    }

    fun clockTime(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

    fun cost(value: Double): String {
        if (value <= 0) return ""
        return if (value < 0.01) String.format(Locale.US, "$%.4f", value) else String.format(Locale.US, "$%.3f", value)
    }

    fun tokens(value: Long): String = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
        else -> value.toString()
    }
}
