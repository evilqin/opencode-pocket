package dev.pocket.opencode.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocket.opencode.core.PocketEngine
import dev.pocket.opencode.core.ServerPhase
import dev.pocket.opencode.ui.components.EmptyState

/**
 * 官方 Web UI：直接开容器里 `opencode web` 提供的页面，
 * 移动端体验已经不错，这里只补一点滚动条/回弹的视觉处理。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(engine: PocketEngine, onBack: () -> Unit) {
    val context = LocalContext.current
    val server by engine.server.status.collectAsStateWithLifecycle()
    var progress by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }

    BackHandler {
        val view = webView
        if (view?.canGoBack() == true) view.goBack() else onBack()
    }

    // 只在用户点刷新 / 重试时 reload 一次
    LaunchedEffect(reloadToken) {
        if (reloadToken > 0) {
            webView?.reload()
        }
    }

    // 端口变了（改设置后重启服务）：让 WebView 指向新地址
    val pageUrl = "http://127.0.0.1:${server.port}/"
    LaunchedEffect(pageUrl) {
        if (reloadToken > 0 || server.phase == ServerPhase.RUNNING) {
            webView?.loadUrl(pageUrl)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            PocketIcons.Globe,
                            null,
                            Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "127.0.0.1:${server.port}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                IconButton(onClick = { reloadToken++ }) {
                    Icon(Icons.Filled.Refresh, "刷新")
                }
                IconButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${server.port}/"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) {
                    Icon(PocketIcons.External, "用浏览器打开")
                }
            }
        }

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }

        when {
            server.phase != ServerPhase.RUNNING -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = PocketIcons.Globe,
                        title = "服务未在运行",
                        body = server.error ?: "先在首页启动 OpenCode 服务，这里的网页界面就会连上。",
                        action = {
                            Button(onClick = { engine.startServer() }) { Text("启动服务") }
                        },
                    )
                }
            }

            else -> {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.allowFileAccess = false
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.setSupportZoom(false)
                                settings.textZoom = 100
                                webView = this
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        failed = null
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        view?.evaluateJavascript(MOBILE_TWEAKS, null)
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        if (request?.isForMainFrame == true) {
                                            failed = "页面加载失败：${error?.description ?: "未知错误"}"
                                        }
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val url = request?.url?.toString() ?: return false
                                        return if (url.contains("127.0.0.1") || url.contains("localhost")) {
                                            false
                                        } else {
                                            runCatching {
                                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                            }
                                            true
                                        }
                                    }
                                }
                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                    }
                                }
                                loadUrl(pageUrl)
                            }
                        },
                        update = { _ ->
                            // 地址变化由 pageUrl 的 LaunchedEffect 处理
                        },
                        onRelease = { view ->
                            webView = null
                            view.stopLoading()
                            view.loadUrl("about:blank")
                            view.destroy()
                        },
                    )
                    if (failed != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            EmptyState(
                                icon = PocketIcons.Globe,
                                title = "加载失败",
                                body = failed ?: "",
                                action = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { reloadToken++ }) { Text("重试") }
                                        Button(onClick = { engine.startServer(restart = true) }) {
                                            Text("重启服务")
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val MOBILE_TWEAKS = """
(function() {
  var style = document.getElementById('pocket-mobile-tweaks');
  if (!style) {
    style = document.createElement('style');
    style.id = 'pocket-mobile-tweaks';
    style.textContent = [
      '::-webkit-scrollbar{width:0;height:0}',
      'html{-webkit-text-size-adjust:100%;}',
      'body{overscroll-behavior:none;}',
      'textarea,input{font-size:16px !important;}'
    ].join('');
    document.head.appendChild(style);
  }
})();
"""
