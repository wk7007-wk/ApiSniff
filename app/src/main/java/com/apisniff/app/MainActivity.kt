package com.apisniff.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.apisniff.app.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val requests = mutableListOf<CapturedRequest>()
    private lateinit var adapter: RequestListAdapter
    private var isRecording = true
    private var requestCounter = 0
    @Volatile private var currentMainHost = ""
    private var interceptorJs = ""
    private var iframeInterceptorJs = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // JS 파일을 assets에서 한번만 로드
        interceptorJs = assets.open("interceptor.js").bufferedReader().readText()
        iframeInterceptorJs = assets.open("iframe_interceptor.js").bufferedReader().readText()

        setupRecyclerView()
        setupWebView()
        setupControls()
    }

    private fun setupRecyclerView() {
        adapter = RequestListAdapter(
            onClick = { req ->
                val intent = Intent(this, RequestDetailActivity::class.java).apply {
                    putExtra("id", req.id)
                    putExtra("type", req.type)
                    putExtra("method", req.method)
                    putExtra("url", req.url)
                    putExtra("status", req.status)
                    putExtra("reqHeaders", req.requestHeaders)
                    putExtra("reqBody", req.requestBody)
                    putExtra("resBody", req.responseBody)
                    putExtra("timestamp", req.timestamp)
                }
                startActivity(intent)
            },
            onLongClick = { req ->
                val json = requestToJson(req).toString(2)
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("API", json))
                Toast.makeText(this, "JSON 복사됨", Toast.LENGTH_SHORT).show()
            }
        )
        binding.rvRequests.layoutManager = LinearLayoutManager(this)
        binding.rvRequests.adapter = adapter
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

            addJavascriptInterface(JsBridge(), "ApiSniff")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    binding.etUrl.setText(url)
                    try { currentMainHost = java.net.URL(url).host } catch (e: Exception) {}
                    if (isRecording) injectInterceptor()
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!isRecording) return null
                    val url = request.url.toString()
                    val method = request.method ?: "GET"
                    val headers = request.requestHeaders

                    // 1) cross-origin HTML에 JS 인터셉터 주입 시도
                    val injected = tryInjectIntoHtml(url, request)
                    if (injected != null) return injected

                    // 2) 노이즈 필터링
                    if (isNoiseUrl(url)) return null

                    // 3) 네이티브 레벨 캡처
                    val headersStr = headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: ""
                    val req = CapturedRequest(
                        id = ++requestCounter,
                        type = "native",
                        method = method.uppercase(),
                        url = url,
                        status = 0,
                        requestHeaders = headersStr.take(2000)
                    )
                    runOnUiThread {
                        val isDuplicate = requests.any { it.url == url && it.type != "native" &&
                            Math.abs(it.timestamp - req.timestamp) < 3000 }
                        if (!isDuplicate) {
                            requests.add(0, req)
                            adapter.addItem(req)
                            updateCount()
                            binding.rvRequests.scrollToPosition(0)
                        }
                    }
                    return null
                }
            }
            webChromeClient = WebChromeClient()
        }
    }

    /** 서브프레임(iframe/fetch) HTML 응답에 JS 인터셉터 주입 (wujie 등) */
    private fun tryInjectIntoHtml(url: String, request: WebResourceRequest): WebResourceResponse? {
        // 메인 프레임 네비게이션은 onPageFinished + evaluateJavascript로 처리
        if (request.isForMainFrame) return null
        if (url.contains("/api/") || isNoiseUrl(url)) return null
        val ext = (Uri.parse(url).path ?: "/").substringAfterLast('.', "").lowercase()
        if (ext in listOf("js", "css", "json", "png", "jpg", "gif", "svg", "woff", "woff2", "ttf", "ico", "map", "webp")) return null

        try {
            val conn = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method ?: "GET"
                instanceFollowRedirects = true
                connectTimeout = 10000
                readTimeout = 10000
            }
            request.requestHeaders?.forEach { (key, value) ->
                if (key.lowercase() != "accept-encoding") conn.setRequestProperty(key, value)
            }
            conn.setRequestProperty("Accept-Encoding", "identity")
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }

            val responseCode = conn.responseCode
            val responseMessage = conn.responseMessage ?: "OK"
            val contentType = conn.contentType ?: ""

            if (!contentType.contains("text/html")) {
                conn.disconnect()
                return null
            }

            val html = (if (responseCode >= 400) conn.errorStream else conn.inputStream)
                ?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""

            // 응답 쿠키 저장
            conn.headerFields?.forEach { (key, values) ->
                if (key?.lowercase() == "set-cookie") {
                    values.forEach { CookieManager.getInstance().setCookie(url, it) }
                }
            }

            // 응답 헤더 (CSP 제거)
            val respHeaders = mutableMapOf<String, String>()
            conn.headerFields?.forEach { (key, values) ->
                if (key != null && values.isNotEmpty()) {
                    val lk = key.lowercase()
                    if (lk !in listOf("content-encoding", "content-length", "content-security-policy", "transfer-encoding")) {
                        respHeaders[key] = values.last()
                    }
                }
            }
            conn.disconnect()

            // JS 주입
            val scriptTag = "<script>$iframeInterceptorJs</script>"
            val headRegex = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
            val injectedHtml = if (headRegex.containsMatchIn(html)) {
                val match = headRegex.find(html)!!
                html.substring(0, match.range.last + 1) + scriptTag + html.substring(match.range.last + 1)
            } else {
                "$scriptTag$html"
            }

            return WebResourceResponse(
                "text/html", "utf-8", responseCode, responseMessage,
                respHeaders,
                java.io.ByteArrayInputStream(injectedHtml.toByteArray(Charsets.UTF_8))
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun setupControls() {
        binding.btnGo.setOnClickListener { loadUrl() }
        binding.etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                loadUrl(); true
            } else false
        }
        binding.btnRecord.setOnClickListener {
            isRecording = !isRecording
            binding.tvRecStatus.text = if (isRecording) "● REC" else "○ STOP"
            binding.tvRecStatus.setTextColor(if (isRecording) 0xFFFF4444.toInt() else 0xFF888888.toInt())
            binding.btnRecord.text = if (isRecording) "중지" else "시작"
            if (isRecording) injectInterceptor()
        }
        binding.btnClear.setOnClickListener {
            requests.clear()
            adapter.clear()
            requestCounter = 0
            updateCount()
            Toast.makeText(this, "캡처 삭제", Toast.LENGTH_SHORT).show()
        }
        binding.btnSave.setOnClickListener { saveToJson() }
    }

    private fun loadUrl() {
        var url = binding.etUrl.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        binding.webView.loadUrl(url)
    }

    private fun injectInterceptor() {
        binding.webView.evaluateJavascript(interceptorJs, null)
    }

    private fun isNoiseUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".ico") ||
            lower.endsWith(".css") || lower.endsWith(".woff") || lower.endsWith(".woff2") ||
            lower.endsWith(".ttf") || lower.endsWith(".svg") || lower.endsWith(".eot")) return true
        if (lower.endsWith(".js") || lower.endsWith(".js.map")) return true
        if (lower.contains("jslog") || lower.contains("web-log") || lower.contains("/weblog/") ||
            lower.contains("gtm") || lower.contains("analytics") || lower.contains("pixel") ||
            lower.contains("favicon") || lower.contains("beacon") || lower.contains("telemetry") ||
            lower.contains("logging") || lower.contains("tracker")) return true
        if (lower.contains("sensor_data") || lower.contains("akamai")) return true
        if (lower.contains("ljc.coupang") || lower.contains("/weblog/submit")) return true
        if (lower.contains("doubleclick") || lower.contains("googlesyndication") ||
            lower.contains("google-analytics") || lower.contains("googletagmanager") ||
            lower.contains("facebook.com/tr") || lower.contains("fbevents")) return true
        return false
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onRequest(type: String, method: String, url: String, status: Int,
                      reqHeaders: String, reqBody: String, resBody: String) {
            if (!isRecording) return
            if (isNoiseUrl(url)) return
            val req = CapturedRequest(
                id = ++requestCounter, type = type, method = method.uppercase(),
                url = url, status = status, requestHeaders = reqHeaders,
                requestBody = reqBody, responseBody = resBody
            )
            runOnUiThread {
                val existing = requests.find { it.url == url && it.type == "native" &&
                    Math.abs(it.timestamp - req.timestamp) < 3000 }
                if (existing != null) {
                    val idx = requests.indexOf(existing)
                    requests[idx] = req.copy(id = existing.id)
                    adapter.submitList(requests.toList())
                } else {
                    requests.add(0, req)
                    adapter.addItem(req)
                }
                updateCount()
                binding.rvRequests.scrollToPosition(0)
            }
        }
    }

    private fun updateCount() {
        val total = requests.size
        val api = requests.count { it.url.contains("/api/") || it.requestBody.isNotEmpty() }
        binding.tvCount.text = "${total}건 (API: ${api})"
    }

    private fun requestToJson(req: CapturedRequest): JSONObject {
        return JSONObject().apply {
            put("id", req.id); put("type", req.type); put("method", req.method)
            put("url", req.url); put("status", req.status)
            put("requestHeaders", req.requestHeaders); put("requestBody", req.requestBody)
            put("responseBody", req.responseBody); put("timestamp", req.timestamp)
            put("time", req.timeStr)
        }
    }

    private fun saveToJson() {
        if (requests.isEmpty()) {
            Toast.makeText(this, "캡처된 요청 없음", Toast.LENGTH_SHORT).show()
            return
        }
        val domain = requests.firstOrNull()?.domain ?: "unknown"
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val filename = "${domain}_${sdf.format(Date())}.json"
        val dir = File("/sdcard/Download/ApiSniff")
        dir.mkdirs()
        val file = File(dir, filename)
        val json = JSONObject().apply {
            put("domain", domain)
            put("capturedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date()))
            put("totalRequests", requests.size)
            put("requests", JSONArray().apply { requests.forEach { put(requestToJson(it)) } })
        }
        file.writeText(json.toString(2))
        Toast.makeText(this, "저장: $filename", Toast.LENGTH_LONG).show()
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack()
        else super.onBackPressed()
    }
}
