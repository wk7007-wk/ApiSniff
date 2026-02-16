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
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val requests = mutableListOf<CapturedRequest>()
    private lateinit var adapter: RequestListAdapter
    private var isRecording = true
    private var requestCounter = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                    if (isRecording) injectInterceptor()
                }

                // 네이티브 레벨 HTTP 인터셉터 — JS 컨텍스트 격리(wujie 등)와 무관하게 모든 요청 캡처
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!isRecording) return null
                    val url = request.url.toString()
                    val method = request.method ?: "GET"
                    val headers = request.requestHeaders

                    // 노이즈 필터링
                    if (isNoiseUrl(url)) return null

                    val headersStr = headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: ""

                    val req = CapturedRequest(
                        id = ++requestCounter,
                        type = "native",
                        method = method.uppercase(),
                        url = url,
                        status = 0,  // 네이티브 인터셉트에서는 응답 상태를 알 수 없음
                        requestHeaders = headersStr.take(2000),
                        requestBody = "",  // 네이티브 인터셉트에서는 요청 바디를 알 수 없음
                        responseBody = ""  // 응답 바디도 알 수 없음 — JS 인터셉터가 보완
                    )

                    runOnUiThread {
                        // JS 인터셉터가 같은 URL을 이미 잡았으면 중복 방지
                        val isDuplicate = requests.any { it.url == url && it.type != "native" &&
                            Math.abs(it.timestamp - req.timestamp) < 3000 }
                        if (!isDuplicate) {
                            requests.add(0, req)
                            adapter.addItem(req)
                            updateCount()
                            binding.rvRequests.scrollToPosition(0)
                        }
                    }

                    return null  // 요청을 수정하지 않고 그대로 통과
                }
            }
            webChromeClient = WebChromeClient()
        }
    }

    private fun setupControls() {
        binding.btnGo.setOnClickListener { loadUrl() }

        binding.etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                loadUrl()
                true
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
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        binding.webView.loadUrl(url)
    }

    private fun injectInterceptor() {
        val js = """
            (function() {
                if (window.__apiSniffInjected) return;
                window.__apiSniffInjected = true;

                // fetch 가로채기
                var origFetch = window.fetch;
                window.fetch = function(input, init) {
                    var url = (typeof input === 'string') ? input : (input.url || '');
                    var method = (init && init.method) || 'GET';
                    var reqBody = '';
                    var reqHeaders = '';
                    try {
                        if (init && init.body) reqBody = String(init.body).substring(0, 2000);
                        if (init && init.headers) {
                            var h = init.headers;
                            if (h instanceof Headers) {
                                var pairs = [];
                                h.forEach(function(v, k) { pairs.push(k + ': ' + v); });
                                reqHeaders = pairs.join('\n');
                            } else if (typeof h === 'object') {
                                reqHeaders = Object.keys(h).map(function(k) { return k + ': ' + h[k]; }).join('\n');
                            }
                        }
                    } catch(e) {}

                    return origFetch.apply(this, arguments).then(function(resp) {
                        var status = resp.status;
                        resp.clone().text().then(function(body) {
                            try {
                                ApiSniff.onRequest('fetch', method, url, status,
                                    reqHeaders.substring(0, 2000), reqBody.substring(0, 2000),
                                    body.substring(0, 5000));
                            } catch(e) {}
                        }).catch(function(){});
                        return resp;
                    }).catch(function(err) {
                        try {
                            ApiSniff.onRequest('fetch', method, url, 0, reqHeaders, reqBody, 'ERROR: ' + err.message);
                        } catch(e) {}
                        throw err;
                    });
                };

                // XMLHttpRequest 가로채기
                var origOpen = XMLHttpRequest.prototype.open;
                var origSend = XMLHttpRequest.prototype.send;
                var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;

                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__sniff_method = method;
                    this.__sniff_url = url;
                    this.__sniff_headers = [];
                    return origOpen.apply(this, arguments);
                };

                XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                    if (this.__sniff_headers) this.__sniff_headers.push(name + ': ' + value);
                    return origSetHeader.apply(this, arguments);
                };

                XMLHttpRequest.prototype.send = function(body) {
                    var self = this;
                    var reqBody = body ? String(body).substring(0, 2000) : '';
                    var reqHeaders = (this.__sniff_headers || []).join('\n').substring(0, 2000);
                    this.addEventListener('load', function() {
                        try {
                            ApiSniff.onRequest('xhr', self.__sniff_method || '?',
                                self.__sniff_url || '', self.status,
                                reqHeaders, reqBody,
                                (self.responseText || '').substring(0, 5000));
                        } catch(e) {}
                    });
                    return origSend.apply(this, arguments);
                };

                // shadow DOM 내 iframe 주입 시도
                try {
                    var allEls = document.querySelectorAll('*');
                    for (var i = 0; i < allEls.length; i++) {
                        if (allEls[i].shadowRoot) {
                            var ifs = allEls[i].shadowRoot.querySelectorAll('iframe');
                            for (var j = 0; j < ifs.length; j++) {
                                try {
                                    var w = ifs[j].contentWindow;
                                    if (w && !w.__apiSniffInjected) {
                                        w.__apiSniffInjected = true;
                                        var of2 = w.fetch;
                                        w.fetch = function(input, init) {
                                            var url2 = (typeof input === 'string') ? input : (input.url || '');
                                            var m2 = (init && init.method) || 'GET';
                                            var b2 = (init && init.body) ? String(init.body).substring(0,2000) : '';
                                            return of2.apply(this, arguments).then(function(r2) {
                                                r2.clone().text().then(function(t2) {
                                                    try { ApiSniff.onRequest('iframe-fetch', m2, url2, r2.status, '', b2, t2.substring(0,5000)); } catch(e){}
                                                });
                                                return r2;
                                            });
                                        };
                                    }
                                } catch(e) {}
                            }
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(js, null)
    }

    /** 노이즈 URL 필터링 — 텔레메트리, 봇 감지, 정적 리소스 제외 */
    private fun isNoiseUrl(url: String): Boolean {
        val lower = url.lowercase()
        // 정적 리소스
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".ico") ||
            lower.endsWith(".css") || lower.endsWith(".woff") || lower.endsWith(".woff2") ||
            lower.endsWith(".ttf") || lower.endsWith(".svg") || lower.endsWith(".eot")) return true
        // JS 파일 (라이브러리/번들)
        if (lower.endsWith(".js") || lower.endsWith(".js.map")) return true
        // 텔레메트리/로그/분석
        if (lower.contains("jslog") || lower.contains("web-log") || lower.contains("/weblog/") ||
            lower.contains("gtm") || lower.contains("analytics") || lower.contains("pixel") ||
            lower.contains("favicon") || lower.contains("beacon") || lower.contains("telemetry") ||
            lower.contains("logging") || lower.contains("tracker")) return true
        // Akamai 봇 감지
        if (lower.contains("sensor_data") || lower.contains("akamai")) return true
        // 쿠팡 텔레메트리
        if (lower.contains("ljc.coupang") || lower.contains("/weblog/submit")) return true
        // 광고/추적
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
                id = ++requestCounter,
                type = type,
                method = method.uppercase(),
                url = url,
                status = status,
                requestHeaders = reqHeaders,
                requestBody = reqBody,
                responseBody = resBody
            )

            runOnUiThread {
                // 네이티브 인터셉터가 같은 URL을 이미 잡았으면 그것을 업데이트 (응답 바디 등 보강)
                val existing = requests.find { it.url == url && it.type == "native" &&
                    Math.abs(it.timestamp - req.timestamp) < 3000 }
                if (existing != null) {
                    val idx = requests.indexOf(existing)
                    requests[idx] = req.copy(id = existing.id)  // JS가 더 상세하므로 교체
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
            put("id", req.id)
            put("type", req.type)
            put("method", req.method)
            put("url", req.url)
            put("status", req.status)
            put("requestHeaders", req.requestHeaders)
            put("requestBody", req.requestBody)
            put("responseBody", req.responseBody)
            put("timestamp", req.timestamp)
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
            put("requests", JSONArray().apply {
                requests.forEach { put(requestToJson(it)) }
            })
        }

        file.writeText(json.toString(2))
        Toast.makeText(this, "저장: $filename", Toast.LENGTH_LONG).show()
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
