package cn.blink

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 二进制文件预览 + 分享（第②个功能）：
 *  - FTP 文件先下载到 App 私有缓存 cacheDir/shared/ 下
 *  - 图片扩展名 → ImageView 预览；其他类型 → 提示"无法预览"
 *  - 「📤 分享」：FileProvider 暴露缓存文件 → 系统分享面板（微信/QQ 直接收）
 *  - 「📥 存到手机」：SAF CreateDocument 选位置，FTP 直接流式写入
 * 零新增权限。
 */
class PreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REMOTE_PATH = "extra_remote_path"
        const val EXTRA_FILE_NAME   = "extra_file_name"
        const val AUTHORITY = "cn.blink.fileprovider"

        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff")
        private const val APK_MIME = "application/vnd.android.package-archive"
    }

    private val ftp = FtpManager
    private var remotePath = ""
    private var fileName = ""
    private var localFile: File? = null

    private lateinit var ivPreview: ImageView
    private lateinit var tvHint: TextView
    private lateinit var tvFileName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnBack: Button
    private lateinit var btnShare: Button
    private lateinit var btnSave: Button
    private lateinit var btnInstall: Button
    private lateinit var layoutApkInfo: LinearLayout
    private lateinit var ivApkIcon: ImageView
    private lateinit var tvApkPackage: TextView
    private lateinit var tvApkVersion: TextView
    private lateinit var tvApkSize: TextView

    /** 待自动续装：从"安装未知应用"设置页返回时，若已授权则自动继续安装 */
    private var pendingInstall: File? = null

    /** 已发给系统安装器的 APK；回到本页（装完/放弃）即删，防止缓存越积越大 */
    private var lastInstallerLaunched: File? = null

    /** APK 解析出的包名（安装时用于提示"装的是自己"） */
    private var apkPackageName: String = ""

    private val pickForSave =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) saveToLocal(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        ivPreview  = findViewById(R.id.iv_preview)
        tvHint     = findViewById(R.id.tv_hint)
        tvFileName = findViewById(R.id.tv_filename)
        tvStatus   = findViewById(R.id.tv_status)
        btnBack    = findViewById(R.id.btn_back)
        btnShare   = findViewById(R.id.btn_share)
        btnSave    = findViewById(R.id.btn_save)
        btnInstall = findViewById(R.id.btn_install)
        layoutApkInfo = findViewById(R.id.layout_apk_info)
        ivApkIcon     = findViewById(R.id.iv_apk_icon)
        tvApkPackage  = findViewById(R.id.tv_apk_package)
        tvApkVersion  = findViewById(R.id.tv_apk_version)
        tvApkSize     = findViewById(R.id.tv_apk_size)

        remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH) ?: ""
        fileName = intent.getStringExtra(EXTRA_FILE_NAME)
            ?: remotePath.substringAfterLast('/')
        tvFileName.text = fileName

        // 只有 .apk 才给安装入口（.aab 不能直接装，只留分享/下载）
        if (extOf(fileName) == "apk") {
            btnInstall.visibility = android.view.View.VISIBLE
        }
        btnBack.setOnClickListener { finish() }
        btnShare.setOnClickListener { shareFile() }
        btnSave.setOnClickListener { pickForSave.launch(fileName) }
        btnInstall.setOnClickListener { confirmAndInstall() }

        loadAndPreview()
    }

    private fun loadAndPreview() {
        tvStatus.text = "下载中..."
        lifecycleScope.launch {
            if (!ftp.ensureConnected()) {
                tvStatus.text = "连接失败：" + ftp.lastError
                return@launch
            }
            val sharedDir = File(cacheDir, "shared")
            sharedDir.mkdirs()
            // 该目录只服务本页的 FTP 下载，换新文件前清掉旧的（含上次残留的安装包/图片）
            sharedDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            val f = File(sharedDir, fileName)
            val ok = ftp.download(remotePath, f)
            if (!ok) {
                tvStatus.text = "下载失败：" + ftp.lastError
                return@launch
            }
            localFile = f
            if (extOf(fileName) in IMAGE_EXTS) {
                tvStatus.text = ""
                // 后台线程解码，避免大图卡 UI
                withContext(Dispatchers.Default) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bmp = BitmapFactory.decodeFile(f.absolutePath, opts)
                    withContext(Dispatchers.Main) {
                        if (bmp != null) ivPreview.setImageBitmap(bmp)
                        else {
                            ivPreview.visibility = android.view.View.GONE
                            tvHint.visibility = android.view.View.VISIBLE
                            tvHint.text = "图片解码失败（可能已损坏）"
                        }
                    }
                }
            } else if (extOf(fileName) == "apk") {
                // APK：解析包体信息填信息卡（仿 ES）
                tvStatus.text = ""
                ivPreview.visibility = android.view.View.GONE
                tvHint.visibility = android.view.View.VISIBLE
                tvHint.text = "「$fileName」\nAPK 安装包\n点击下方「📦 安装」"
                showApkInfo(f)
            } else {
                tvStatus.text = ""
                ivPreview.visibility = android.view.View.GONE
                tvHint.visibility = android.view.View.VISIBLE
                tvHint.text = "「$fileName」\n此类型无法预览\n可点下方「📤 分享」或「📥 存到手机」"
            }
        }
    }

    /** 分享：FileProvider 暴露缓存文件 → 系统分享面板 */
    private fun shareFile() {
        val f = localFile ?: return
        tvStatus.text = "准备分享..."
        try {
            val uri = FileProvider.getUriForFile(this, AUTHORITY, f)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(fileName)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "分享到"))
        } catch (e: Exception) {
            tvStatus.text = "分享失败：" + e.message
        }
    }

    /** 存到手机：SAF 选定目标 → FTP 流式下载写入 */
    private fun saveToLocal(target: Uri) {
        tvStatus.text = "保存到手机..."
        lifecycleScope.launch {
            if (!ftp.ensureConnected()) {
                tvStatus.text = "连接失败：" + ftp.lastError
                return@launch
            }
            val done = try {
                contentResolver.openOutputStream(target, "w")?.let { out ->
                    ftp.downloadToStream(remotePath, out).also { out.close() }
                } ?: false
            } catch (_: Exception) { false }
            tvStatus.text = if (done) "✓ 已保存到手机" else "保存失败：" + ftp.lastError
        }
    }

    /** 解析 APK 包体（PackageManager 读真实数据，不靠文件名猜），填信息卡 */
    private fun showApkInfo(f: File) {
        val pm = packageManager
        var icon: Drawable? = null
        try {
            val info = pm.getPackageArchiveInfo(f.absolutePath, 0)
            apkPackageName = info?.packageName ?: ""
            tvApkPackage.text = "包名：${info?.packageName ?: fileName}"
            tvApkVersion.text = "版本：${info?.versionName ?: "未知"}（code ${info?.versionCode ?: "?"}）"
            try {
                info?.applicationInfo?.let { icon = pm.getApplicationIcon(it) }
            } catch (_: Exception) { }
        } catch (_: Exception) {
            // 解析失败也显示基础信息，不阻断安装
            apkPackageName = ""
            tvApkPackage.text = "包名：$fileName"
            tvApkVersion.text = "版本：未知"
        }
        tvApkSize.text = "大小：${formatSize(f.length())}"
        ivApkIcon.setImageDrawable(icon)
        layoutApkInfo.visibility = android.view.View.VISIBLE
    }

    /** 点"安装"：先弹确认框（写明要装什么；装的是本应用则警告会自动重启），确认后才调系统安装器 */
    private fun confirmAndInstall() {
        val f = localFile ?: run {
            tvStatus.text = "文件尚未下载完成"
            return
        }
        val selfPkg = packageName
        val installingSelf = apkPackageName.isNotEmpty() && apkPackageName == selfPkg
        val msg = buildString {
            append("将安装：").append(apkPackageName.ifEmpty { fileName }).append("\n")
            append("（").append(fileName).append("）\n\n")
            if (installingSelf) {
                append("⚠️ 这是 Blink 本身。安装成功后本应用会自动重启并重新打开，这是正常现象，不会丢失你的连接配置。")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("安装应用")
            .setMessage(msg)
            .setPositiveButton("安装") { _, _ -> startSystemInstaller(f) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 调起系统安装器。Android 8+ 需已授予"安装未知应用"权限，否则 vivo 上
     *  ACTION_VIEW 会被静默吞掉（ES 有这权限才顺）。没权限时跳到设置页让用户开，
     *  回来后在 onResume 自动续装。 */
    private fun startSystemInstaller(f: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val granted = packageManager.canRequestPackageInstalls()
            if (!granted) {
                pendingInstall = f
                tvStatus.text = "需先开启\"安装未知应用\"，已跳转向导"
                startActivity(Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
        }
        launchInstaller(f)
    }

    /** 真正发安装 intent（权限已确认） */
    private fun launchInstaller(f: File) {
        lastInstallerLaunched = f
        tvStatus.text = ""
        try {
            val uri = FileProvider.getUriForFile(this, AUTHORITY, f)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(install)
        } catch (e: Exception) {
            tvStatus.text = "安装失败：" + e.message
        }
    }

    /** 从其他页面返回：
     *  ① 从"安装未知应用"设置页回来且已授权 → 自动继续安装；
     *  ② 从系统安装器回来（装完/放弃）→ 删掉已发的安装包，防止缓存越积越大。 */
    override fun onResume() {
        super.onResume()
        val f = pendingInstall
        pendingInstall = null
        if (f != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.canRequestPackageInstalls()) {
            launchInstaller(f)
            return
        }
        val installed = lastInstallerLaunched
        if (installed != null) {
            installed.delete()
            lastInstallerLaunched = null
            if (installed == localFile) {
                localFile = null
                tvStatus.text = "安装流程结束，已清理本地安装包"
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1_048_576L -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / 1_048_576.0)
    }

    private fun extOf(name: String) = name.substringAfterLast('.', "").lowercase()
    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(name))
            ?: "application/octet-stream"
}
