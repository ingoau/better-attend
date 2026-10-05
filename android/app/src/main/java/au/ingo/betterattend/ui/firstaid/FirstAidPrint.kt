package au.ingo.betterattend.ui.firstaid

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Renders [html] in an off-screen WebView and hands it to the system print dialog, where it can be
 * printed or saved as PDF. The WebView is kept alive until the page has loaded and the job is queued.
 */
object FirstAidPrint {
    /** Held so the WebView isn't garbage-collected before printing starts. */
    @SuppressLint("StaticFieldLeak") // released as soon as the page has loaded
    private var pending: WebView? = null

    fun print(context: Context, jobName: String, html: String): Boolean {
        val activity = context.findActivity() ?: return false
        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return false
        val webView = WebView(activity)
        // Static content only: no scripts, no network, no file access.
        webView.settings.javaScriptEnabled = false
        webView.settings.allowFileAccess = false
        webView.settings.blockNetworkLoads = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val adapter = view.createPrintDocumentAdapter(jobName)
                runCatching { printManager.print(jobName, adapter, null) }
                pending = null
            }
        }
        pending = webView
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        return true
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
