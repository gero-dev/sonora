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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.sonora.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

private data class WebAuthData(
    val cookie: String,
    val visitorData: String,
    val dataSyncId: String?,
    val authUser: String,
)

/**
 * Вход в Google для личной библиотеки YouTube Music.
 *
 * Это обычный WebView с accounts.google.com, как в Metrolist: после входа
 * приложение забирает cookie (SAPISID) и передаёт его InnerTube. Пароль
 * и токены Google приложению не доступны — только cookie текущей сессии.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GoogleLoginScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun checkLogin(view: WebView) {
        if (checking) return
        checking = true
        error = null
        scope.launch {
            repeat(20) { attempt ->
                val auth = extractAuth(view)
                if (auth != null) {
                    vm.completeGoogleLogin(
                        cookie = auth.cookie,
                        visitorData = auth.visitorData,
                        dataSyncId = auth.dataSyncId,
                        authUser = auth.authUser,
                    )
                    delay(600)
                    checking = false
                    onDone()
                    return@launch
                }
                if (attempt == 19) {
                    checking = false
                    error = "Не удалось получить cookie Google. " +
                        "Убедитесь, что вход завершён на странице music.youtube.com."
                } else {
                    delay(1_000)
                }
            }
        }
    }

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
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
            }
            Column(Modifier.weight(1f)) {
                Text("Вход в Google", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Личная библиотека YouTube Music: плейлисты и «Понравившиеся»",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(12.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                TextButton(
                    onClick = {
                        val view = webView ?: return@TextButton
                        checkLogin(view)
                    },
                ) {
                    Text("Проверить вход")
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val errorText = error
        if (!errorText.isNullOrBlank()) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
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
                            cacheMode = WebSettings.LOAD_DEFAULT
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                if (url?.contains("music.youtube.com") == true && !started) {
                                    started = true
                                    checkLogin(this@apply)
                                }
                            }
                        }
                        webChromeClient = WebChromeClient()
                        loadUrl(
                            "https://accounts.google.com/ServiceLogin" +
                                "?continue=https%3A%2F%2Fmusic.youtube.com",
                        )
                        webView = this
                    }
                },
            )
        }
    }
}

private suspend fun extractAuth(view: WebView): WebAuthData? = withContext(Dispatchers.Main) {
    val cookie = CookieManager.getInstance()
        .getCookie("https://music.youtube.com")
        .orEmpty()
    if (!cookie.contains("SAPISID") && !cookie.contains("PAPISID")) {
        return@withContext null
    }

    val script =
        "(function(){try{var c=(window.yt&&window.yt.config_)||{};" +
            "return JSON.stringify({v:c.VISITOR_DATA||''," +
            "d:(c.DATASYNC_ID||'').split('||')[0]," +
            "a:String(c.SESSION_INDEX||0)});}catch(e){return '{}';}})()"
    val raw = suspendCancellableCoroutine<String?> { continuation ->
        view.evaluateJavascript(script) { value -> continuation.resume(value) }
    }
    val decoded = runCatching {
        val element = Json.parseToJsonElement(raw.orEmpty())
        if (element is JsonPrimitive) element.contentOrNull else null
    }.getOrNull() ?: return@withContext null
    val obj = runCatching { Json.parseToJsonElement(decoded).jsonObject }.getOrNull()
        ?: return@withContext null
    val visitor = obj["v"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: return@withContext null
    WebAuthData(
        cookie = cookie,
        visitorData = visitor,
        dataSyncId = obj["d"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        authUser = obj["a"]?.jsonPrimitive?.contentOrNull
            ?.filter { it.isDigit() }
            ?.ifBlank { "0" }
            ?: "0",
    )
}
