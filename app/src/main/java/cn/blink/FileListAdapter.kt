package cn.blink

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class FileListAdapter(
    private val onClick: (FtpEntry) -> Unit,
    private val onLongClick: ((FtpEntry) -> Boolean)? = null
) : RecyclerView.Adapter<FileListAdapter.VH>() {

    private var items: List<FtpEntry> = emptyList()

    fun submitList(new: List<FtpEntry>) {
        items = new
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
    )

    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tv_name)
        private val tvMeta: TextView = view.findViewById(R.id.tv_meta)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
            onLongClick?.let { cb ->
                itemView.setOnLongClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) cb(items[pos]) else false
                }
            }
        }

        fun bind(entry: FtpEntry) {
            tvName.text = if (entry.isDirectory) entry.name + "/" else entry.name
            tvMeta.text = if (entry.isDirectory) "目录" else {
                val size = formatSize(entry.size)
                val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(Date(entry.lastModified))
                "$size  $date"
            }
        }
    }

    private fun formatSize(b: Long): String = when {
        b < 1024L -> "$b B"
        b < 1_048_576L -> "${b / 1024} KB"
        else -> "%.1f MB".format(b / 1_048_576.0)
    }
}
