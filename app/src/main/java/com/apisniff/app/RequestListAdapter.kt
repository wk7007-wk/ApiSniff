package com.apisniff.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RequestListAdapter(
    private val onClick: (CapturedRequest) -> Unit,
    private val onLongClick: (CapturedRequest) -> Unit
) : RecyclerView.Adapter<RequestListAdapter.ViewHolder>() {

    private val items = mutableListOf<CapturedRequest>()

    fun submitList(list: List<CapturedRequest>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun addItem(item: CapturedRequest) {
        items.add(0, item)
        notifyItemInserted(0)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_request, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvMethod: TextView = view.findViewById(R.id.tvMethod)
        private val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        private val tvUrl: TextView = view.findViewById(R.id.tvUrl)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        private val tvType: TextView = view.findViewById(R.id.tvType)

        fun bind(req: CapturedRequest) {
            tvMethod.text = req.method
            tvMethod.setTextColor(req.methodColor)
            tvStatus.text = if (req.status > 0) req.status.toString() else "..."
            tvStatus.setTextColor(req.statusColor)
            tvUrl.text = req.shortUrl
            tvTime.text = req.timeStr
            tvType.text = req.type

            // API 호출은 밝게, 기타는 흐리게
            val isApiLike = req.url.contains("/api/") || req.url.contains("json") ||
                req.method in listOf("POST", "PUT", "PATCH", "DELETE") ||
                req.type == "fetch" || req.type == "xhr"
            val alpha = if (isApiLike) 1f else 0.6f
            itemView.alpha = alpha

            // native 타입은 배경색 구분
            if (req.type == "native") {
                tvType.setTextColor(0xFFFF9800.toInt())  // orange for native
            } else {
                tvType.setTextColor(0xFF888888.toInt())
            }

            itemView.setOnClickListener { onClick(req) }
            itemView.setOnLongClickListener { onLongClick(req); true }
        }
    }
}
