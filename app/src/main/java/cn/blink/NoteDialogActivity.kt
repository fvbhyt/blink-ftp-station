package cn.blink

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快速记录输入页（点桌面小组件卡片弹出，无标题栏 dialog 样式）：
 *  - 输入框：自动带时间戳前缀（可删）
 *  - 目标路径：示例 根目录/目录/目录/文件.txt，可改
 *  - "记到FTP"：后台走 FtpManager.appendLine（RETR→拼接→STOR，仅用已验证可靠的命令）
 *  - 记完刷新小组件预览，并 Toast 结果。
 *  零新增权限：纯网络 + 本地 Prefs。
 */
class NoteDialogActivity : AppCompatActivity() {

    private val ftp = FtpManager

    private lateinit var etNote: EditText
    private lateinit var etPath: EditText
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_dialog)
        etNote = findViewById(R.id.et_note)
        etPath = findViewById(R.id.et_note_path)
        btnSave = findViewById(R.id.btn_note_save)
        btnCancel = findViewById(R.id.btn_note_cancel)
        tvStatus = findViewById(R.id.tv_note_status)

        // 冷启动喂账号：小组件直接拉起本 Activity 时进程全新、FtpManager 内存里没有 config。
        // 从 Prefs 读回已保存的账号，让 ensureConnected() 有重连原料（没重装过就通）；
        // 刚卸载重装 Prefs 为空，则保持 null，记 FTP 时提示"请先在主页连接"。
        ftp.primeConfig(this)

        // 默认目标路径：已设定过 → 预填记住的/最后浏览目录；首次使用 → 留空，
        // 让框内 hint（通用示例 根目录/目录/目录/文件.txt）直接可见，由用户填写。
        val firstTime = !Prefs.hasNoteSetting(this)
        if (!firstTime) etPath.setText(Prefs.loadNotePath(this))

        // 首次使用：不猜环境，提示用户设定一次目标路径；"记到FTP"成功后即永久记住。
        if (firstTime) {
            tvStatus.text = "首次使用：请填写目标路径（记下后即记住，之后无需再填）"
            tvStatus.setTextColor(0xFFB45309.toInt())   // 琥珀色醒目
        }

        // 自动带时间戳前缀（用户可整段删掉重输）
        val ts = SimpleDateFormat("MM-dd HH:mm ", Locale.getDefault()).format(Date())
        etNote.setText(ts)
        etNote.setSelection(0)
        etNote.requestFocus()

        btnSave.setOnClickListener { doSave() }
        btnCancel.setOnClickListener { finish() }
    }

    private fun doSave() {
        val text = etNote.text.toString().trim()
        // 根相对路径：剥掉用户照示例填的前导 "/"（/a/b → a/b）
        val path = etPath.text.toString().trim().removePrefix("/").ifEmpty { Prefs.loadNotePath(this) }
        if (text.isEmpty()) {
            tvStatus.text = "内容为空，没记"
            return
        }
        btnSave.isEnabled = false
        tvStatus.text = "记到 FTP（$path）..."

        lifecycleScope.launch {
            val ok = if (ftp.ensureConnected()) {
                ftp.appendLine(path, text)
            } else false
            if (ok) {
                // 记成功后才"记住"目标路径（失败不污染设定，下次仍提示首设）
                Prefs.saveNotePath(this@NoteDialogActivity, path)
                Prefs.saveLastNote(this@NoteDialogActivity, text)
                QuickNoteWidget.refreshAll(this@NoteDialogActivity)
                // 广播兜底（多实例场景）
                sendBroadcast(Intent(QuickNoteWidget.ACTION_NOTE_CHANGED).setPackage(packageName))
                tvStatus.text = "✓ 已记到 $path"
            } else {
                tvStatus.text = ftp.lastError.ifEmpty { "记录失败" }
            }
            btnSave.isEnabled = true
        }
    }
}
