package cn.blink

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val ftp = FtpManager
    private var currentPath = "."

    // 连接表单
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var chkGbk: CheckBox
    private lateinit var btnConnect: Button
    private lateinit var layoutForm: ScrollView

    // 文件列表
    private lateinit var etPath: EditText
    private lateinit var btnRefresh: Button
    private lateinit var btnReconnect: Button
    private lateinit var btnUpload: Button
    private lateinit var btnDownload: Button
    private lateinit var btnUp: Button
    private lateinit var fileList: RecyclerView
    private lateinit var layoutFiles: LinearLayout
    private lateinit var tvStatus: TextView

    private lateinit var fileAdapter: FileListAdapter

    // 中转站状态：待下载的远端路径（点"下载"后先记下，等用户在系统里选好保存位置再写）
    private var pendingDownloadRemote: String? = null

    // SAF 启动器：零存储权限，系统文件选择器拿 content URI
    // 上传：选一个或多个手机文件 → 传到当前 FTP 目录
    private val pickForUpload =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) uploadFromUris(uris)
        }
    // 下载：用户在系统里选"存到哪个文件" → 拿到目标 content URI → 把远端文件写进去
    private val pickForSave =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) saveRemoteToUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etHost   = findViewById(R.id.et_host)
        etPort   = findViewById(R.id.et_port)
        etUser   = findViewById(R.id.et_user)
        etPass   = findViewById(R.id.et_pass)
        chkGbk   = findViewById(R.id.chk_gbk)
        btnConnect = findViewById(R.id.btn_connect)
        layoutForm = findViewById(R.id.layout_form)

        etPath     = findViewById(R.id.et_path)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnReconnect = findViewById(R.id.btn_reconnect)
        btnUpload    = findViewById(R.id.btn_upload)
        btnDownload  = findViewById(R.id.btn_download)
        btnUp        = findViewById(R.id.btn_up)
        fileList   = findViewById(R.id.file_list)
        layoutFiles = findViewById(R.id.layout_files)
        tvStatus   = findViewById(R.id.tv_status)

        fileAdapter = FileListAdapter(
            onClick = { entry ->
                if (entry.isDirectory) {
                    currentPath = if (currentPath == ".") entry.name
                                  else "$currentPath/${entry.name}"
                    etPath.setText(currentPath)
                    loadFileList()
                } else {
                    openFile(currentPath, entry.name)
                }
            },
            onLongClick = { entry ->
                // 长按 → 弹出操作菜单（文件：打开/下载/重命名/删除；目录：进入）
                showEntryMenu(entry)
                true
            }
        )
        fileList.layoutManager = LinearLayoutManager(this)
        fileList.adapter = fileAdapter

        btnConnect.setOnClickListener { connectFromForm() }
        btnRefresh.setOnClickListener { loadFileList() }
        btnUp.setOnClickListener { goUpOneLevel() }
        btnUpload.setOnClickListener { pickForUpload.launch(arrayOf("*/*")) }
        btnDownload.setOnClickListener { pickDownloadTarget() }
        btnReconnect.setOnClickListener {
            ftp.disconnect()
            layoutFiles.visibility = View.GONE
            layoutForm.visibility = View.VISIBLE
            tvStatus.text = ""
        }

        tryAutoConnect()
    }

    // ── 自动连接 ────────────────────────────────────────────────

    private fun tryAutoConnect() {
        val cfg = Prefs.loadConfig(this)
        if (cfg == null) {
            layoutForm.visibility = View.VISIBLE
            layoutFiles.visibility = View.GONE
            return
        }
        tvStatus.text = "自动连接中..."
        lifecycleScope.launch {
            val ok = ftp.connect(cfg)
            if (ok) {
                showFileView()
                loadFileList()
                // 点开即用：自动打开上次编辑的文件
                Prefs.loadLastFile(this@MainActivity)?.let { lastPath ->
                    openFile(pathOf(lastPath).first, pathOf(lastPath).second)
                }
            } else {
                tvStatus.text = ftp.lastError.ifEmpty { "自动连接失败，请手动输入" }
                layoutForm.visibility = View.VISIBLE
                layoutFiles.visibility = View.GONE
            }
        }
    }

    // 拆分 "dir/file" → (dir, file)；"." 表示根目录
    private fun pathOf(remotePath: String): Pair<String, String> {
        val idx = remotePath.lastIndexOf('/')
        if (idx == -1) return "." to remotePath
        return remotePath.substring(0, idx) to remotePath.substring(idx + 1)
    }

    // ── 手动连接 ────────────────────────────────────────────────

    private fun connectFromForm() {
        val host = etHost.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull() ?: 21
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()
        val enc  = if (chkGbk.isChecked) "GBK" else "UTF-8"

        if (host.isEmpty()) {
            tvStatus.text = "请填写服务器地址"
            return
        }

        val cfg = FtpConfig(host, port, user, pass, enc)
        Prefs.saveConfig(this, cfg)

        tvStatus.text = "连接中..."
        layoutForm.visibility = View.GONE
        layoutFiles.visibility = View.VISIBLE

        lifecycleScope.launch {
            val ok = ftp.connect(cfg)
            if (ok) {
                tvStatus.text = "已连接 $host:$port"
                loadFileList()
            } else {
                tvStatus.text = ftp.lastError.ifEmpty { "连接失败，请检查地址或账号密码" }
                layoutForm.visibility = View.VISIBLE
                layoutFiles.visibility = View.GONE
            }
        }
    }

    private fun showFileView() {
        layoutForm.visibility = View.GONE
        layoutFiles.visibility = View.VISIBLE
    }

    // ── 文件列表 ────────────────────────────────────────────────

    private fun loadFileList() {
        val path = etPath.text.toString().trim().ifEmpty { "." }
        currentPath = path
        tvStatus.text = "加载文件列表..."

        lifecycleScope.launch {
            if (!ftp.ensureConnected()) {
                tvStatus.text = "连接已断开且重连失败：" + ftp.lastError
                return@launch
            }
            val files = ftp.listFiles(path)
            fileAdapter.submitList(files)
            // 记住最后浏览的目录：小组件"快速记录"默认记到这里的 quick-note.txt
            Prefs.saveLastBrowseDir(this@MainActivity, path)
            tvStatus.text = "共 ${files.size} 项"
        }
    }

    // ── 中转站：手机文件 ⇄ FTP ─────────────────────────────────

    /** 上传：把用户在系统里选的一个/多个手机文件，逐个传到当前 FTP 目录 */
    private fun uploadFromUris(uris: List<Uri>) {
        tvStatus.text = "上传 ${uris.size} 个文件..."
        lifecycleScope.launch {
            val ok = ftp.ensureConnected()
            if (!ok) { tvStatus.text = "连接失败：" + ftp.lastError; return@launch }
            val dir = currentPath
            var done = 0
            var failed = 0
            for (uri in uris) {
                val name = displayNameOf(uri)
                val remote = if (dir == ".") name else "$dir/$name"
                val okOne = try {
                    contentResolver.openInputStream(uri)?.let { input ->
                        ftp.uploadFromStream(input, remote).also { input.close() }
                    } ?: false
                } catch (_: Exception) { false }
                if (okOne) done++ else failed++
                tvStatus.text = "上传中 $done/${uris.size}..."
            }
            tvStatus.text = if (failed == 0) "✓ 已上传 $done 个到 $dir"
                            else "上传完成：$done 成功，$failed 失败"
        }
    }

    /** 选文件名（OpenableColumns.DISPLAY_NAME，读不到就退回 URI 末段） */
    private fun displayNameOf(uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { c ->
            val idx = c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst()) c.getString(idx)?.let { return it }
        }
        val seg = uri.lastPathSegment
        return seg?.substringAfterLast('/')?.ifEmpty { null } ?: "file"
    }

    /** 下载按钮：先确认"存到哪个文件"（弹框提示），再让系统选保存位置 */
    private fun pickDownloadTarget() {
        // 当前目录里的"选中文件"没有单选状态，所以这里走：
        // 让用户先知道要下载什么。简化版：提示用长按列表项下载；
        // 点本按钮则把"当前路径"下第一个可下载文件下载——不，改为：
        // 弹框列出当前目录文件，让用户点选要下载哪个。
        val path = etPath.text.toString().trim().ifEmpty { "." }
        lifecycleScope.launch {
            val ok = ftp.ensureConnected()
            if (!ok) { tvStatus.text = "连接失败：" + ftp.lastError; return@launch }
            val files = ftp.listFiles(path).filter { !it.isDirectory }
            if (files.isEmpty()) {
                tvStatus.text = "当前目录没有可下载的文件"
                return@launch
            }
            val names = files.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("下载到手机")
                .setItems(names) { _, i ->
                    startDownload(path, files[i].name)
                }
                .show()
        }
    }

    /** 真正执行下载：远端 remotePath → 用户选定的目标 content URI */
    private fun startDownload(dirPath: String, fileName: String) {
        val remote = if (dirPath == ".") fileName else "$dirPath/$fileName"
        pendingDownloadRemote = remote
        pickForSave.launch(fileName)
    }

    /** 用户在系统里选好了"存到哪个文件"，把远端文件写进去 */
    private fun saveRemoteToUri(target: Uri) {
        val remote = pendingDownloadRemote ?: return
        tvStatus.text = "下载中..."
        lifecycleScope.launch {
            val ok = ftp.ensureConnected()
            if (!ok) { tvStatus.text = "连接失败：" + ftp.lastError; return@launch }
            val done = try {
                contentResolver.openOutputStream(target, "w")?.let { out ->
                    ftp.downloadToStream(remote, out).also { out.close() }
                } ?: false
            } catch (_: Exception) { false }
            tvStatus.text = if (done) "✓ 已保存到手机（$remote）"
                            else "下载失败：${ftp.lastError.ifEmpty { "写入失败" }}"
            pendingDownloadRemote = null
        }
    }

    // ── 打开文件（拦截二进制）────────────────────────────────────

    private val BINARY_EXTS = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff",
        "mp4", "avi", "mkv", "mov", "3gp", "flv", "wmv",
        "mp3", "wav", "flac", "aac", "ogg", "wma",
        "apk", "aab", "jar", "zip", "rar", "7z", "gz", "tar",
        "exe", "msi", "dmg", "deb", "rpm",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "bin", "dat", "iso"
    )

    private fun isBinary(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in BINARY_EXTS
    }

    private fun openFile(dirPath: String, fileName: String) {
        val remotePath = if (dirPath == ".") fileName else "$dirPath/$fileName"
        if (isBinary(fileName)) {
            // 二进制（图片/APK/压缩等）→ 预览 + 分享界面
            startActivity(
                Intent(this, PreviewActivity::class.java)
                    .putExtra(PreviewActivity.EXTRA_REMOTE_PATH, remotePath)
                    .putExtra(PreviewActivity.EXTRA_FILE_NAME, fileName)
            )
            return
        }
        Prefs.saveLastFile(this, remotePath)

        startActivity(
            Intent(this, EditorActivity::class.java)
                .putExtra(EditorActivity.EXTRA_REMOTE_PATH, remotePath)
                .putExtra(EditorActivity.EXTRA_FILE_NAME, fileName)
        )
    }

    // ── 上级目录 ────────────────────────────────────────────────

    private fun goUpOneLevel() {
        val path = etPath.text.toString().trim()
        if (path == "." || path.isEmpty()) return  // 已在根
        val parent = path.substringBeforeLast('/', "")
        etPath.setText(parent.ifEmpty { "." })
        loadFileList()
    }

    // ── 长按菜单：删除 / 重命名 / 移动 ───────────────────────────

    private fun remoteOf(name: String) = if (currentPath == ".") name else "$currentPath/$name"

    /** 长按条目：文件 → 操作菜单；目录 → 进入目录 */
    private fun showEntryMenu(entry: FtpEntry) {
        if (entry.isDirectory) {
            val sub = if (currentPath == ".") entry.name else "$currentPath/${entry.name}"
            etPath.setText(sub)
            loadFileList()
            return
        }
        val remote = remoteOf(entry.name)
        val items = arrayOf("✎ 重命名", "⇄ 移动到其他目录", "🗑 删除")
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> promptRename(remote, entry.name)
                    1 -> promptMove(remote)
                    2 -> confirmDelete(remote, entry.name)
                }
            }
            .show()
    }

    /** 重命名：只改当前目录下的名字（同一目录内） */
    private fun promptRename(remote: String, name: String) {
        val input = EditText(this)
        input.setText(name)
        input.setSelection(input.text.length)
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("重命名") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == name) {
                    tvStatus.text = "未改动文件名"
                    return@setPositiveButton
                }
                // 目标必须写"全路径"（源所在目录 + 新名）。只写名字会被服务器当成
                // "相对根目录移动"，方向错 → 550/503（ES 能重命名是因为它发的就是全路径）。
                val dir = if (remote.contains('/')) remote.substringBeforeLast('/') else "."
                val target = if (dir == ".") newName else "$dir/$newName"
                runRename(remote, target)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 移动：把文件移到指定目录（填目录路径，可多层，如 a/b） */
    private fun promptMove(remote: String) {
        val input = EditText(this)
        input.hint = "目标目录路径（相对 FTP 根，如 a/b）"
        AlertDialog.Builder(this)
            .setTitle("移动到目录")
            .setView(input)
            .setMessage("只填目录，文件名保持不变。填 \".\" 表示移到 FTP 根目录")
            .setPositiveButton("移动") { _, _ ->
                val dir = input.text.toString().trim()
                val fileName = remote.substringAfterLast('/')
                val target = if (dir == ".") fileName else "$dir/$fileName"
                runRename(remote, target)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 实际发 REN 命令（重命名和移动共用；to 为相对根的全路径） */
    private fun runRename(remote: String, target: String) {
        lifecycleScope.launch {
            if (!ftp.ensureConnected()) { tvStatus.text = ftp.lastError; return@launch }
            val ok = ftp.renameFile(remote, target)
            tvStatus.text = if (ok) "✓ 已重命名/移动" else ftp.lastError.ifEmpty { "操作失败" }
            if (ok) loadFileList()
        }
    }

    /** 删除：二次确认（文件删掉不可恢复） */
    private fun confirmDelete(remote: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("删除文件")
            .setMessage("确定删除 $name ？此操作不可恢复")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    if (!ftp.ensureConnected()) { tvStatus.text = ftp.lastError; return@launch }
                    val ok = ftp.deleteFile(remote)
                    tvStatus.text = if (ok) "✓ 已删除 $name" else ftp.lastError.ifEmpty { "删除失败" }
                    if (ok) loadFileList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
