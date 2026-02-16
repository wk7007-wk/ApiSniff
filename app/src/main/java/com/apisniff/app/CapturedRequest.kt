package com.apisniff.app

data class CapturedRequest(
    val id: Int,
    val type: String,
    val method: String,
    val url: String,
    val status: Int = 0,
    val requestHeaders: String = "",
    val requestBody: String = "",
    val responseBody: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    val shortUrl: String
        get() {
            return try {
                val u = java.net.URL(url)
                val path = u.path
                if (path.length > 40) "..." + path.takeLast(37) else path
            } catch (e: Exception) {
                if (url.length > 40) "..." + url.takeLast(37) else url
            }
        }

    val domain: String
        get() {
            return try {
                java.net.URL(url).host
            } catch (e: Exception) {
                ""
            }
        }

    val timeStr: String
        get() {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            return sdf.format(java.util.Date(timestamp))
        }

    val statusColor: Int
        get() = when {
            status in 200..299 -> 0xFF4CAF50.toInt()  // green
            status in 300..399 -> 0xFFFF9800.toInt()  // orange
            status in 400..599 -> 0xFFF44336.toInt()  // red
            else -> 0xFF9E9E9E.toInt()                 // gray
        }

    val methodColor: Int
        get() = when (method.uppercase()) {
            "GET" -> 0xFF2196F3.toInt()     // blue
            "POST" -> 0xFF4CAF50.toInt()    // green
            "PUT" -> 0xFFFF9800.toInt()     // orange
            "DELETE" -> 0xFFF44336.toInt()  // red
            "PATCH" -> 0xFF9C27B0.toInt()   // purple
            else -> 0xFF9E9E9E.toInt()
        }
}
