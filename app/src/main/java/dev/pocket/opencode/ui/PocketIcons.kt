package dev.pocket.opencode.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 只补充 material-icons-core 里没有的图标（避免引入 30MB 的 icons-extended）。
 * 全部按 24x24 视口手绘，色调由 Icon(tint=...) 统一上色。
 */
object PocketIcons {

    private fun vector(
        name: String,
        block: ImageVector.Builder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

    private fun ImageVector.Builder.stroke(d: String, width: Float = 1.7f) = addPath(
        pathData = addPathNodes(d),
        stroke = SolidColor(Color.White),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )

    private fun ImageVector.Builder.fill(d: String) = addPath(
        pathData = addPathNodes(d),
        fill = SolidColor(Color.White),
    )

    val Forum: ImageVector by lazy {
        vector("forum") {
            stroke("M4.2,4.5h15.6a2,2 0 0 1 2,2v8.2a2,2 0 0 1 -2,2h-8.4l-4.6,3.6v-3.6H4.2a2,2 0 0 1 -2,-2V6.5a2,2 0 0 1 2,-2z")
            stroke("M6.5,8.6h11")
            stroke("M6.5,12.4h7")
        }
    }

    val Terminal: ImageVector by lazy {
        vector("terminal") {
            stroke("M3.2,4.6h17.6a1.8,1.8 0 0 1 1.8,1.8v11.2a1.8,1.8 0 0 1 -1.8,1.8H3.2a1.8,1.8 0 0 1 -1.8,-1.8V6.4a1.8,1.8 0 0 1 1.8,-1.8z")
            stroke("M6.4,9.2l3.4,3 -3.4,3")
            stroke("M12.4,15.2h5.2")
        }
    }

    val Stop: ImageVector by lazy {
        vector("stop") {
            fill("M7.2,5.4h9.6a1.8,1.8 0 0 1 1.8,1.8v9.6a1.8,1.8 0 0 1 -1.8,1.8H7.2a1.8,1.8 0 0 1 -1.8,-1.8V7.2a1.8,1.8 0 0 1 1.8,-1.8z")
        }
    }

    val Copy: ImageVector by lazy {
        vector("copy") {
            stroke("M9.2,4.2h7.6a2.4,2.4 0 0 1 2.4,2.4v7.6")
            stroke("M5.6,7.8h8.8a2,2 0 0 1 2,2v8.4a2,2 0 0 1 -2,2H5.6a2,2 0 0 1 -2,-2V9.8a2,2 0 0 1 2,-2z")
        }
    }

    val External: ImageVector by lazy {
        vector("external") {
            stroke("M14.4,4.2h5.4v5.4")
            stroke("M19.8,4.2l-8.2,8.2")
            stroke("M17.6,13.4v5a2,2 0 0 1 -2,2H5.8a2,2 0 0 1 -2,-2V8.6a2,2 0 0 1 2,-2h5")
        }
    }

    val Eye: ImageVector by lazy {
        vector("eye") {
            stroke("M2.4,12S6,6.4 12,6.4 21.6,12 21.6,12 18,17.6 12,17.6 2.4,12 2.4,12z")
            stroke("M12,12m-2.6,0a2.6,2.6 0 1,0 5.2,0a2.6,2.6 0 1,0 -5.2,0")
        }
    }

    val EyeOff: ImageVector by lazy {
        vector("eye-off") {
            stroke("M3.4,3.4l17.2,17.2")
            stroke("M10.4,6.6a9,9 0 0 1 1.6,-0.2c6,0 9.6,5.6 9.6,5.6a16.4,16.4 0 0 1 -3.2,3.8")
            stroke("M6.4,8C3.9,9.7 2.4,12 2.4,12S6,17.6 12,17.6c1.2,0 2.3,-0.2 3.2,-0.6")
        }
    }

    val Folder: ImageVector by lazy {
        vector("folder") {
            fill("M3.4,6.6a2,2 0 0 1 2,-2h3.4a1.6,1.6 0 0 1 1.2,0.6l1.5,1.9h7.1a2,2 0 0 1 2,2v8.3a2,2 0 0 1 -2,2H5.4a2,2 0 0 1 -2,-2V6.6z")
        }
    }

    val Download: ImageVector by lazy {
        vector("download") {
            stroke("M12,3.6v10.4")
            stroke("M7.4,9.8l4.6,4.6 4.6,-4.6")
            stroke("M4.2,19.6h15.6")
        }
    }

    val Bolt: ImageVector by lazy {
        vector("bolt") {
            fill("M13.4,2.2L4.6,13.4h5.2l-1.2,8.4 8.8,-11.2h-5.2l1.2,-8.4z")
        }
    }

    val Power: ImageVector by lazy {
        vector("power") {
            stroke("M12,3.4v7.6")
            stroke("M7.1,6.2a8.2,8.2 0 1 0 9.8,0")
        }
    }

    val Storage: ImageVector by lazy {
        vector("storage") {
            stroke("M3.4,7.2a2,2 0 0 1 2,-2h13.2a2,2 0 0 1 2,2v3.2H3.4V7.2z")
            stroke("M3.4,13.6h17.2v3.2a2,2 0 0 1 -2,2H5.4a2,2 0 0 1 -2,-2v-3.2z")
            stroke("M6.6,8.8h0.02")
            stroke("M6.6,15.2h0.02")
        }
    }

    val Chip: ImageVector by lazy {
        vector("chip") {
            stroke("M7,7h10v10H7z")
            stroke("M9.8,10.6h4.4v2.8H9.8z")
            stroke("M10,4.2v2.8M14,4.2v2.8M10,17v2.8M14,17v2.8M4.2,10h2.8M4.2,14h2.8M17,10h2.8M17,14h2.8")
        }
    }

    val Code: ImageVector by lazy {
        vector("code") {
            stroke("M8.4,6.6L2.9,12l5.5,5.4")
            stroke("M15.6,6.6L21.1,12l-5.5,5.4")
        }
    }

    val Sliders: ImageVector by lazy {
        vector("sliders") {
            stroke("M4,7.4h16")
            stroke("M4,16.6h16")
            stroke("M9,7.4m-1.8,0a1.8,1.8 0 1,0 3.6,0a1.8,1.8 0 1,0 -3.6,0")
            stroke("M15,16.6m-1.8,0a1.8,1.8 0 1,0 3.6,0a1.8,1.8 0 1,0 -3.6,0")
        }
    }

    val Shield: ImageVector by lazy {
        vector("shield") {
            stroke("M12,3l7.2,2.8v5.6c0,4.2 -3.1,7.4 -7.2,9.2 -4.1,-1.8 -7.2,-5 -7.2,-9.2V5.8L12,3z")
            stroke("M8.8,11.8l2.2,2.2 4.2,-4.4")
        }
    }

    val Gauge: ImageVector by lazy {
        vector("gauge") {
            stroke("M5.4,18.6a8.4,8.4 0 1 1 13.2,0")
            stroke("M12,14.6l4.2,-4.4")
        }
    }

    val Globe: ImageVector by lazy {
        vector("globe") {
            stroke("M12,12m-8.4,0a8.4,8.4 0 1,0 16.8,0a8.4,8.4 0 1,0 -16.8,0")
            stroke("M3.6,12h16.8")
            stroke("M12,3.6c2.6,2.6 2.6,14.2 0,16.8 -2.6,-2.6 -2.6,-14.2 0,-16.8z")
        }
    }
}
