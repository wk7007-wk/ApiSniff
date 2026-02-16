package com.apisniff.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.apisniff.app.databinding.ActivityDetailBinding
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RequestDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val method = intent.getStringExtra("method") ?: "?"
        val url = intent.getStringExtra("url") ?: ""
        val status = intent.getIntExtra("status", 0)
        val type = intent.getStringExtra("type") ?: ""
        val reqHeaders = intent.getStringExtra("reqHeaders") ?: ""
        val reqBody = intent.getStringExtra("reqBody") ?: ""
        val resBody = intent.getStringExtra("resBody") ?: ""
        val timestamp = intent.getLongExtra("timestamp", 0)
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        val shortUrl = try {
            java.net.URL(url).path.let { if (it.length > 35) "..." + it.takeLast(32) else it }
        } catch (e: Exception) { url.takeLast(35) }

        binding.tvTitle.text = "$method $shortUrl"
        binding.tvMethod.text = method
        binding.tvStatus.text = if (status > 0) status.toString() else "?"
        binding.tvTime.text = sdf.format(Date(timestamp))
        binding.tvType.text = type
        binding.tvUrl.text = url
        binding.tvReqHeaders.text = if (reqHeaders.isNotEmpty()) reqHeaders else "(없음)"
        binding.tvReqBody.text = formatJson(reqBody)
        binding.tvResBody.text = formatJson(resBody)

        // 색상
        binding.tvMethod.setTextColor(CapturedRequest(0, type, method, url, status).methodColor)
        binding.tvStatus.setTextColor(CapturedRequest(0, type, method, url, status).statusColor)

        binding.btnCopy.setOnClickListener { copyJson(method, url, status, type, reqHeaders, reqBody, resBody, timestamp) }
        binding.btnSave.setOnClickListener { saveJson(method, url, status, type, reqHeaders, reqBody, resBody, timestamp) }
    }

    private fun formatJson(text: String): String {
        if (text.isEmpty()) return "(없음)"
        return try {
            if (text.trimStart().startsWith("{")) JSONObject(text).toString(2)
            else if (text.trimStart().startsWith("[")) org.json.JSONArray(text).toString(2)
            else text
        } catch (e: Exception) { text }
    }

    private fun buildJson(method: String, url: String, status: Int, type: String,
                          reqHeaders: String, reqBody: String, resBody: String, timestamp: Long): String {
        return JSONObject().apply {
            put("method", method)
            put("url", url)
            put("status", status)
            put("type", type)
            put("requestHeaders", reqHeaders)
            put("requestBody", reqBody)
            put("responseBody", resBody)
            put("timestamp", timestamp)
        }.toString(2)
    }

    private fun copyJson(method: String, url: String, status: Int, type: String,
                         reqHeaders: String, reqBody: String, resBody: String, timestamp: Long) {
        val json = buildJson(method, url, status, type, reqHeaders, reqBody, resBody, timestamp)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("API Request", json))
        Toast.makeText(this, "JSON 복사됨", Toast.LENGTH_SHORT).show()
    }

    private fun saveJson(method: String, url: String, status: Int, type: String,
                         reqHeaders: String, reqBody: String, resBody: String, timestamp: Long) {
        val json = buildJson(method, url, status, type, reqHeaders, reqBody, resBody, timestamp)
        val domain = try { java.net.URL(url).host } catch (e: Exception) { "unknown" }
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val filename = "${method}_${domain}_${sdf.format(Date(timestamp))}.json"

        val dir = File("/sdcard/Download/ApiSniff")
        dir.mkdirs()
        File(dir, filename).writeText(json)
        Toast.makeText(this, "저장: $filename", Toast.LENGTH_LONG).show()
    }
}
