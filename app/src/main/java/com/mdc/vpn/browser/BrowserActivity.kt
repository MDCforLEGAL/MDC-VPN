package com.mdc.vpn.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mdc.vpn.R
import com.mdc.vpn.adblock.AdBlocker
import com.mdc.vpn.databinding.ActivityBrowserBinding
import com.mdc.vpn.settings.AppSettings
import com.mdc.vpn.util.Links
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Uygulama ici tarayici.
 *
 * WebView tabanli oldugu icin Chrome'un aksine reklam/izleyici engelleme
 * gomulu gelir: AdGuard DNS filtresi (190.000+ alan adi) hem ag isteklerini
 * keser hem de yaygin reklam alanlarini gizler. Ucuncu taraf cerezleri kapalidir.
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private val blocked = AtomicInteger(0)
    private val emptyResponse by lazy {
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureWebView()
        bindToolbar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        updateBlockerUi()
        if (savedInstanceState == null) {
            binding.webView.loadUrl(Links.PERSONAL_LINKS)
        } else {
            binding.webView.restoreState(savedInstanceState)
        }
    }

    private fun bindToolbar() {
        binding.closeButton.setOnClickListener { finish() }
        binding.reloadButton.setOnClickListener {
            if (binding.progress.visibility == View.VISIBLE) {
                binding.webView.stopLoading()
            } else {
                binding.webView.reload()
            }
        }
        binding.urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                binding.webView.loadUrl(normalize(binding.urlBar.text.toString()))
                binding.urlBar.clearFocus()
                true
            } else {
                false
            }
        }
        binding.blockerChip.setOnClickListener {
            AppSettings.setAdBlock(this, !AppSettings.adBlock(this))
            updateBlockerUi()
        }
        binding.openExternalButton.setOnClickListener { currentUrl()?.let { openExternally(it) } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setGeolocationEnabled(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            settings.forceDark = WebSettings.FORCE_DARK_AUTO
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, false)
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (request.isForMainFrame) return null
                if (!AppSettings.adBlock(this@BrowserActivity)) return null
                val host = request.url?.host ?: return null
                if (!AdBlocker.isBlocked(this@BrowserActivity, host)) return null
                val count = blocked.incrementAndGet()
                if (count % 5 == 0) runOnUiThread { renderBlockedCount() }
                return emptyResponse
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                binding.progress.visibility = View.VISIBLE
                if (!binding.urlBar.hasFocus()) binding.urlBar.setText(url.orEmpty())
            }

            override fun onPageFinished(view: WebView, url: String?) {
                binding.progress.visibility = View.GONE
                if (!binding.urlBar.hasFocus()) binding.urlBar.setText(url.orEmpty())
                if (AppSettings.adBlock(this@BrowserActivity)) {
                    view.evaluateJavascript(COSMETIC_FILTER, null)
                }
                renderBlockedCount()
            }
        }

        binding.webView.setDownloadListener { url, _, _, _, _ ->
            // Indirmeler Chrome'a devredilir; WebView'un kendi indiricisi yok.
            openExternally(url)
        }
    }

    private fun updateBlockerUi() {
        val enabled = AppSettings.adBlock(this)
        binding.blockerChip.text = getString(
            if (enabled) R.string.browser_blocker_on else R.string.browser_blocker_off
        )
        binding.blockerChip.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.accent else R.color.text_secondary
            )
        )
        renderBlockedCount()
    }

    private fun renderBlockedCount() {
        binding.blockedCount.text = getString(R.string.browser_blocked_count, blocked.get())
    }

    private fun currentUrl(): String? =
        binding.webView.url ?: binding.urlBar.text?.toString()?.takeIf { it.isNotBlank() }

    private fun openExternally(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, R.string.browser_external_failed, Toast.LENGTH_SHORT).show() }
    }

    private fun normalize(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return Links.PERSONAL_LINKS
        if (text.startsWith("http://") || text.startsWith("https://")) return text
        if (text.startsWith("about:") || text.startsWith("file:")) return text
        return if (text.contains(' ') || !text.contains('.')) {
            "https://duckduckgo.com/?q=" + Uri.encode(text)
        } else {
            "https://$text"
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onDestroy() {
        binding.webView.loadUrl("about:blank")
        binding.webView.destroy()
        super.onDestroy()
    }

    private companion object {
        /** Reklam alanlarini gizleyen kozmetik filtre (AdGuard kozmetik kurallari). */
        const val COSMETIC_FILTER = """
            (function(){
              var selectors=[".adsbygoogle","ins.adsbygoogle","[id^='google_ads']",
                "[id^='div-gpt-ad']","[data-ad-slot]","[data-ad-client]",
                "iframe[src*='doubleclick.net']","iframe[src*='/ads/']",
                "[class*='ad-banner']","[class*='ad-container']","[class*='adsbygoogle']",
                "[id*='taboola']","[class*='taboola']","[id*='outbrain']","[class*='outbrain']",
                "[aria-label='Advertisement']","[class*='sponsored']"];
              for(var i=0;i<selectors.length;i++){
                var nodes=document.querySelectorAll(selectors[i]);
                for(var j=0;j<nodes.length;j++){nodes[j].style.setProperty('display','none','important');}
              }
            })();
        """
    }
}
