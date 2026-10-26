/*
 * Ported from Metrolist (https://github.com/mostafaalagamy/Metrolist), GPL-3.0.
 * Adapted for Sonora: Timber -> android.util.Log, no global proxy.
 */

package app.sonora.data.remote.youtube.innertube.potoken

class PoTokenException(message: String) : Exception(message)

// to be thrown if the WebView provided by the system is broken
class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception {
    return if (error.contains("SyntaxError"))
        BadWebViewException(error)
    else
        PoTokenException(error)
}
