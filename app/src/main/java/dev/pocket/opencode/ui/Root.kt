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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocket.opencode.core.PocketEngine
import dev.pocket.opencode.core.ServerPhase

sealed interface Route {
    val name: String

    data object Home : Route {
        override val name = HomeName
    }

    data object Chat : Route {
        override val name = ChatName
    }

    data object Terminal : Route {
        override val name = TerminalName
    }

    data object Settings : Route {
        override val name = SettingsName
    }

    data object Setup : Route {
        override val name = SetupName
    }

    data object Web : Route {
        override val name = WebName
    }

    companion object {
        const val HomeName = "home"
        const val ChatName = "chat"
        const val TerminalName = "terminal"
        const val SettingsName = "settings"
        const val SetupName = "setup"
        const val WebName = "web"

        fun fromName(value: String): Route = when (value) {
            ChatName -> Chat
            TerminalName -> Terminal
            SettingsName -> Settings
            SetupName -> Setup
            WebName -> Web
            else -> Home
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketRoot(engine: PocketEngine) {
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val install by engine.installer.state.collectAsStateWithLifecycle()
    val server by engine.server.status.collectAsStateWithLifecycle()
    val initialized by engine.initialized.collectAsStateWithLifecycle()

    // 用字符串保存路由，进程被回收后也能回到原来那一页
    var routeName by rememberSaveable { mutableStateOf(Route.HomeName) }
    val route: Route = Route.fromName(routeName)
    var welcomed by rememberSaveable { mutableStateOf(false) }
    var autoStartDone by rememberSaveable { mutableStateOf(false) }

    // 首次打开且环境缺失：直接进安装页（必须等初始化完成，否则默认值会把老用户丢进安装页）
    LaunchedEffect(initialized, install.installed) {
        if (!initialized || welcomed) return@LaunchedEffect
        welcomed = true
        if (!install.installed) routeName = Route.SetupName
    }

    // 自动启动服务（进入 App 时）——同样要等设置真正读出来
    LaunchedEffect(initialized, install.installed, settings.autoStart) {
        if (!initialized || autoStartDone) return@LaunchedEffect
        autoStartDone = true
        if (install.installed && settings.autoStart && server.phase == ServerPhase.STOPPED) {
            engine.startServer()
        }
    }

    // 快捷方式 / 通知进来的深链：切到对应页面
    val deepLink by dev.pocket.opencode.core.DeepLinks.requested.collectAsStateWithLifecycle()
    LaunchedEffect(deepLink) {
        when (deepLink) {
            "web" -> routeName = Route.WebName
            "setup" -> routeName = Route.SetupName
        }
        if (deepLink != null) dev.pocket.opencode.core.DeepLinks.consume()
    }

    if (route == Route.Web) {
        WebScreen(engine) { routeName = Route.HomeName }
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
                            onClick = { routeName = target.name },
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
                        onOpenSetup = { routeName = Route.SetupName },
                        onOpenWeb = { routeName = Route.WebName },
                        onOpenTerminal = { routeName = Route.TerminalName },
                        onOpenSettings = { routeName = Route.SettingsName },
                    )

                    Route.Chat -> ChatScreen(engine, onOpenSettings = { routeName = Route.SettingsName })
                    Route.Terminal -> TerminalScreen(engine)
                    Route.Settings -> SettingsScreen(engine)
                    Route.Setup -> Column {
                        ScreenTopBar(title = "安装环境", onBack = { routeName = Route.HomeName })
                        SetupScreen(engine) { routeName = Route.HomeName }
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
