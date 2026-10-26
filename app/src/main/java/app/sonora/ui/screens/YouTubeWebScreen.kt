package app.sonora.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.sonora.core.util.AppLinks

/**
 * Встроенный веб-плеер YouTube Music.
 *
 * Это обычный браузер внутри приложения: загружается официальный music.youtube.com,
 * со всей рекламой и без каких-либо модификаций. Войдите в Google-аккаунт прямо на
 * странице — приложение получает доступ к вашей библиотеке ровно так же, как браузер.
 *
 * Ограничения (осознанные, это не баги):
 *  - Google может блокировать вход внутри WebView («браузер может быть небезопасен»);
 *  - воспроизведение работает только пока экран открыт, фонового режима нет;
 *  - никакой блокировки рекламы, извлечения аудио и скачивания нет.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeWebScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val startUrl = "https://music.youtube.com"
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }

    // Аппаратная кнопка «назад» сначала листает историю WebView.
    BackHandler(enabled = true) {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webView?.stopLoading()
                webView?.destroy()
            }
            webView = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
            }
            Column(Modifier.weight(1f)) {
                Text("YouTube Music", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Официальный сайт в браузере приложения · с рекламой и без фона",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            TextButton(onClick = { AppLinks.open(context, startUrl) }) {
                Icon(
                    imageVector = Icons.Rounded.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text("В браузере")
            }
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            cacheMode = WebSettings.LOAD_DEFAULT
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                        // Куки нужны для входа в Google-аккаунт на сайте.
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                            }
                        }
                        webChromeClient = WebChromeClient()
                        loadUrl(startUrl)
                        webView = this
                    }
                },
            )
        }
    }
}
