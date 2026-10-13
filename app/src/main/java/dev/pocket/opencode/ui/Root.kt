package dev.pocket.opencode.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocket.opencode.core.PocketEngine
import dev.pocket.opencode.core.ServerPhase

sealed interface Route {
    data object Home : Route
    data object Chat : Route
    data object Terminal : Route
    data object Settings : Route
    data object Setup : Route
    data object Web : Route
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketRoot(engine: PocketEngine) {
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val install by engine.installer.state.collectAsStateWithLifecycle()
    val server by engine.server.status.collectAsStateWithLifecycle()

    var route by remember { mutableStateOf<Route>(Route.Home) }
    var welcomed by remember { mutableStateOf(false) }
    var autoStartDone by remember { mutableStateOf(false) }

    // 首次打开且环境缺失：直接进安装页
    LaunchedEffect(install.installed) {
        if (!welcomed && !install.installed) {
            welcomed = true
            route = Route.Setup
        } else {
            welcomed = true
        }
    }

    // 自动启动服务（进入 App 时）
    LaunchedEffect(install.installed, settings.autoStart) {
        if (!autoStartDone && install.installed && settings.autoStart &&
            server.phase == ServerPhase.STOPPED
        ) {
            autoStartDone = true
            engine.startServer()
        }
    }

    if (route == Route.Web) {
        WebScreen(engine) { route = Route.Home }
        return
    }

    val tabs = listOf(
        Triple(Route.Home, "首页", Icons.Filled.Home),
        Triple(Route.Chat, "对话", PocketIcons.Forum),
        Triple(Route.Terminal, "终端", PocketIcons.Terminal),
        Triple(Route.Settings, "设置", Icons.Filled.Settings),
    )

    Scaffold(
        topBar = {
            if (route != Route.Setup) {
                CenterAlignedTopAppBar(
                    title = {
                        Column {
                            Text(
                                when (route) {
                                    Route.Chat -> "对话"
                                    Route.Terminal -> "终端与日志"
                                    Route.Settings -> "设置"
                                    else -> "OpenCode 口袋"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subtitle = when {
                                server.phase == ServerPhase.RUNNING -> "运行中 · 127.0.0.1:${server.port}"
                                install.installed -> "已安装，服务未启动"
                                else -> "环境未安装"
                            }
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (route == Route.Home) {
                            IconButton(onClick = { engine.startServer(restart = true) }, enabled = install.installed) {
                                Icon(Icons.Filled.Refresh, "重启服务")
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (route != Route.Setup) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEach { (target, label, icon) ->
                        NavigationBarItem(
                            selected = route == target,
                            onClick = { route = target },
                            icon = { Icon(icon, label) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    (fadeIn() + slideInVertically { it / 12 }) togetherWith
                        (fadeOut() + slideOutVertically { -it / 12 })
                },
                label = "route",
            ) { target ->
                when (target) {
                    Route.Home -> HomeScreen(
                        engine = engine,
                        onOpenSetup = { route = Route.Setup },
                        onOpenWeb = { route = Route.Web },
                        onOpenTerminal = { route = Route.Terminal },
                        onOpenSettings = { route = Route.Settings },
                    )

                    Route.Chat -> ChatScreen(engine, onOpenSettings = { route = Route.Settings })
                    Route.Terminal -> TerminalScreen(engine)
                    Route.Settings -> SettingsScreen(engine)
                    Route.Setup -> Column {
                        ScreenTopBar(title = "安装环境", onBack = { route = Route.Home })
                        SetupScreen(engine) { route = Route.Home }
                    }

                    Route.Web -> Unit
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenTopBar(title: String, onBack: () -> Unit) {
    CenterAlignedTopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        },
    )
}
