package cn.blink

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REMOTE_PATH = "extra_remote_path"
        const val EXTRA_FILE_NAME   = "extra_file_name"

        private val BINARY_EXTS = setOf(
            "jpg","jpeg","png","gif","bmp","webp","tiff",
            "mp4","avi","mkv","mov","3gp","flv","wmv",
            "mp3","wav","flac","aac","ogg","wma",
            "apk","aab","jar","zip","rar","7z","gz","tar",
            "exe","msi","dmg","deb","rpm",
            "pdf","doc","docx","xls","xlsx","ppt","pptx",
            "bin","dat","iso"
        )
    }

    private val ftp = FtpManager
    private var remotePath   = ""
    private var cacheFile: File? = null
    private var charset: Charset = StandardCharsets.UTF_8
    private var dirty = false
    /** 程序 setText 时抑制 dirty，只有用户输入才算"编辑过" */
    @Volatile
    private var suppressDirty = false

    private lateinit var etContent: EditText
    private lateinit var tvFileName: TextView
    private lateinit var tvEncoding: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnBack: Button
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        etContent  = findViewById(R.id.et_content)
        tvFileName = findViewById(R.id.tv_filename)
        tvEncoding = findViewById(R.id.tv_encoding)
        tvStatus   = findViewById(R.id.tv_status)
        btnBack    = findViewById(R.id.btn_back)
        btnSave    = findViewById(R.id.btn_save)

        remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH) ?: ""
        val displayName = intent.getStringExtra(EXTRA_FILE_NAME)
            ?: remotePath.substringAfterLast('/')
        tvFileName.text = displayName

        // 二进制文件防线：万一 Prefs 存了旧路径 / 外部 Intent 传入，直接退回
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext in BINARY_EXTS) {
            tvStatus.text = "这是二进制文件，无法在文本编辑器中打开\n请用主页「下载到手机」保存后再查看"
            btnBack.isEnabled = true
            btnSave.isEnabled = false
            return
        }

        etContent.addTextChangedListener {
            if (!suppressDirty) dirty = true
        }

        btnBack.setOnClickListener {
            if (dirty) showUnsavedDialog() else finish()
        }
        btnSave.setOnClickListener { saveToFile() }

        downloadAndOpen()
    }

    private fun downloadAndOpen() {
        tvStatus.text = "下载中..."
        lifecycleScope.launch {
            // 根治"闲置/隔夜后僵尸 socket"：先确保连接是活的（死→自动重连），
            // 这样重开文件时若 FTP 连接已超时断开也能救回，而不是直接下载失败。
            if (!ftp.ensureConnected()) {
                tvStatus.text = "连接失败：" + ftp.lastError
                return@launch
            }
            val f = File(cacheDir, File(remotePath).name)
            cacheFile = f
            val ok = ftp.download(remotePath, f)
            if (!ok) {
                tvStatus.text = "下载失败"
                return@launch
            }
            val bytes = f.readBytes()
            val enc = EncodingDetector.detect(bytes)
            charset = enc.charset
            tvEncoding.text = "编码：${enc.charsetName}"
            suppressDirty = true
            etContent.setText(String(bytes, charset))
            suppressDirty = false
            tvStatus.text = ""
            dirty = false
        }
    }

    private fun saveToFile() {
        val file = cacheFile ?: return
        val text = etContent.text.toString()

        // 关键防护: 原编码写不下编辑后的文字(如 ASCII 文件加了中文)时，
        // 硬写会把汉字替换成 '?' 造成不可逆损坏 —— 升级 UTF-8 写回。
        var outCharset: Charset = charset
        var upgraded = false
        if (!isLossless(text, outCharset)) {
            outCharset = StandardCharsets.UTF_8
            upgraded = true
        }
        file.writeText(text, outCharset)
        if (upgraded) tvEncoding.text = "编码：${charset.name()} → UTF-8（含原编码无法表示的字符，已升级）"

        tvStatus.text = "上传中..."
        lifecycleScope.launch {
            // 按"先确保连接是新建的"思路：ensureConnected() 会探活，
            // 已死则自动 断开旧 socket→重连→登录（用已存凭据），再上传。
            if (!ftp.ensureConnected()) {
                tvStatus.text = "保存失败：" + ftp.lastError
                return@launch
            }
            val ok = ftp.upload(file, remotePath)
            if (ok) {
                tvStatus.text = "✓ 保存成功"
                dirty = false
                charset = outCharset
            } else {
                // 上传中途连接又断了（极少见）：再抢救一次——重连后重试一遍
                if (ftp.ensureConnected() && ftp.upload(file, remotePath)) {
                    tvStatus.text = "✓ 保存成功"
                    dirty = false
                    charset = outCharset
                } else {
                    tvStatus.text = "保存失败：" + (ftp.lastError.ifEmpty { "连接已断开" })
                }
            }
        }
    }

    /** 该字符集能否无损表示这段文本（往返测试，编不了抛异常/产生替换符） */
    private fun isLossless(text: String, cs: Charset): Boolean {
        if (text.isEmpty()) return true
        return try {
            val b = cs.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
            val s = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(b)
                .toString()
            s == text
        } catch (_: Exception) {
            false
        }
    }

    private fun showUnsavedDialog() {
        AlertDialog.Builder(this)
            .setTitle("未保存")
            .setMessage("编辑内容尚未上传，退出将丢失更改。")
            .setPositiveButton("放弃更改并退出") { _, _ -> finish() }
            .setNegativeButton("继续编辑", null)
            .show()
    }
}
