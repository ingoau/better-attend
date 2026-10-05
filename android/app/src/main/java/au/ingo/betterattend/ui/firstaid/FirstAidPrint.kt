package au.ingo.betterattend.ui.firstaid

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Renders [html] in an off-screen WebView and hands it to the system print dialog, where it can be
 * printed or saved as PDF. One per screen (not a static, so it never holds an Activity): the screen
 * calls [dispose] when it goes, which drops a page still loading. Once the page is handed to the print
 * framework, the WebView is destroyed when that print job finishes.
 */
class FirstAidPrint {
    /** The WebView still loading, held so it isn't garbage-collected before printing starts. */
    private var loading: WebView? = null

    fun print(context: Context, jobName: String, html: String): Boolean {
        val activity = context.findActivity() ?: return false
        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return false
        loading?.destroy()
        val webView = WebView(activity)
        // Static content only: no scripts, no network, no file or content:// access.
        webView.settings.javaScriptEnabled = false
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.blockNetworkLoads = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                // Superseded, disposed, or the Activity went away while it loaded: nothing to print.
                if (loading !== view) return
                loading = null
                if (activity.isFinishing || activity.isDestroyed) {
                    view.destroy()
                    return
                }
                val adapter = DestroyWhenFinished(view.createPrintDocumentAdapter(jobName), view)
                if (runCatching { printManager.print(jobName, adapter, null) }.isFailure) view.destroy()
            }
        }
        loading = webView
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        return true
    }

    /** The screen is going: drop a page that hasn't reached the print dialog yet. */
    fun dispose() {
        loading?.destroy()
        loading = null
    }

    /** Passes everything to the WebView's adapter, then destroys the WebView once the print job is done with it. */
    private class DestroyWhenFinished(private val inner: PrintDocumentAdapter, private val webView: WebView) : PrintDocumentAdapter() {
        override fun onStart() = inner.onStart()

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) = inner.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras)

        override fun onWrite(
            pages: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) = inner.onWrite(pages, destination, cancellationSignal, callback)

        override fun onFinish() {
            inner.onFinish()
            webView.destroy()
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
