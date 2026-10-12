package cn.blink

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

// ── 数据结构 ─────────────────────────────────────────────────────

data class FtpEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

data class FtpConfig(
    val host: String,
    val port: Int = 21,
    val user: String,
    val pass: String,
    val controlEncoding: String = "GBK"   // 控制通道编码，按服务器类型选
)

// ── 单例 FTP 管理器 ─────────────────────────────────────────────
// 两个设计直接对应"编辑画面闪退"的两个根因：
// 1) isAlive() 是 suspend + IO 线程：旧版在主线程做阻塞 sendNoOp（20s），
//    连接僵死时卡死主线程 → ANR → 系统杀进程"闪退重启"。
// 2) 共享 FTPClient 用 synchronized 串行化：MainActivity(listFiles) 与
//    EditorActivity(upload/download) 并发操作同一个非线程安全的 client 会竞态崩溃。
// 所有网络调用都在 Dispatchers.IO，绝不落主线程。

object FtpManager {
    private val client = FTPClient()
    private val lock = Any()          // 串行化所有 FTPClient 访问
    private var config: FtpConfig? = null
    @Volatile
    private var connected = false

    private const val NORMAL_TIMEOUT = 20_000   // 常规读写超时
    private const val CONNECT_TIMEOUT = 10_000  // TCP 建连超时（慢/挂的服务器快速失败，不长等）
    private const val PROBE_TIMEOUT = 3_000     // 探活短超时

    /** 最近一次失败原因（供 UI 直接显示） */
    @Volatile
    var lastError: String = ""

    /** 冷启动喂账号：单例 config 为空调用，从 Prefs 读回已保存的账号。
     *  桌面小组件直接拉起 NoteDialogActivity 时进程全新、config 为空，
     *  调一次就能让 ensureConnected() 拿到重连原料；Prefs 为空（刚卸载重装）则保持 null。 */
    fun primeConfig(ctx: Context) {
        synchronized(lock) { if (config == null) config = Prefs.loadConfig(ctx) }
    }

    /** 把异常翻译成用户可读的中文原因 */
    private fun explain(e: Throwable): String = when (e) {
        is UnknownHostException ->
            "无法解析地址：DNS 找不到 ${e.message?.trim() ?: "该主机"}（域名未配置或当前网段不通）"
        is SocketTimeoutException ->
            "连接超时：能解析但 ${CONNECT_TIMEOUT / 1000} 秒内无响应（被防火墙拦截或端口不对）"
        is ConnectException ->
            "连接被拒绝（${e.message?.trim() ?: "无端口响应"}）：确认端口号，或服务器防火墙未放行此来源"
        is org.apache.commons.net.MalformedServerReplyException ->
            "服务器返回异常响应：${e.message?.lineSequence()?.first() ?: ""}"
        else -> "连接失败：${e.javaClass.simpleName} ${e.message}"
    }

    /** 探活（须在持锁时调用）：短超时 sendNoOp，NAT 掉线 / 僵尸 socket 会快速判死。 */
    private fun probe(): Boolean {
        if (!connected) return false
        return try {
            client.setSoTimeout(PROBE_TIMEOUT)
            val ok = client.sendNoOp()
            if (!ok) connected = false
            ok
        } catch (_: Exception) {
            connected = false
            false
        } finally {
            try { client.setSoTimeout(NORMAL_TIMEOUT) } catch (_: Exception) {}
        }
    }

    /** 连接是否存活（suspend，绝不落主线程）。 */
    suspend fun isAlive(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) { probe() }
    }

    /**
     * 确保有一条"活"连接：
     *   还活着 → 直接复用（最快）；
     *   已死 / 从未连 → 用已保存的凭据自动重连（断开旧 socket + 重新登录）。
     * 这是根治"闲置超时后僵尸 socket 导致下载闪退、保存报连接断开"的关键。
     * 返回操作后连接是否可用。
     */
    suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (probe()) return@withContext true
            val c = config
            if (c == null) { lastError = "无可用连接配置，请先在主页连接"; return@withContext false }
            doConnect(c)
        }
    }

    /** 安全断开（同步、快速，仅本地关 socket） */
    fun disconnect() {
        synchronized(lock) {
            try { if (client.isConnected) client.disconnect() } catch (_: Exception) {}
            connected = false
        }
    }

    /** 建立连接（全新：先清旧 socket 再连）。 */
    suspend fun connect(cfg: FtpConfig): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) { doConnect(cfg) }
    }

    /** 必须持锁。全新连接：清旧 socket → 连接 → 登录 → 配置传输模式。 */
    private fun doConnect(cfg: FtpConfig): Boolean {
        // 先清旧 socket（可能已死）：设短超时，避免 QUIT 卡住
        try {
            if (client.isConnected) {
                try { client.setSoTimeout(PROBE_TIMEOUT) } catch (_: Exception) {}
                client.disconnect()
            }
        } catch (_: Exception) {}
        connected = false
        lastError = ""
        try {
            client.controlEncoding = cfg.controlEncoding
            client.connectTimeout = CONNECT_TIMEOUT  // 建连快速失败（慢服务器/挂的域名不长等）
            client.connect(cfg.host, cfg.port)
            client.setSoTimeout(NORMAL_TIMEOUT)      // 3.11.1：connect 之后调，规避 NPE

            // login() 失败返回 false 而非抛异常，必须显式检查
            val loginOk = client.login(cfg.user, cfg.pass)
            if (!loginOk) {
                val code = client.replyCode
                lastError = when {
                    code == 530 -> "服务器拒绝登录（530）：用户名或密码错误。" +
                            "请核对密码——中文输入法下「！＃＠」等符号容易打成全角，需用英文半角。"
                    else -> "登录被服务器拒绝（reply $code ${client.replyString?.trim() ?: ""}）"
                }
                try { client.disconnect() } catch (_: Exception) {}
                connected = false
                return false
            }
            client.setFileType(FTP.BINARY_FILE_TYPE)   // 文本一律 binary，避免 CRLF 转换
            client.enterLocalPassiveMode()             // NAT 后必须 passive
            // 连接序列与 com.blink 版完全一致：直连 → 登录 → binary → passive，
            // 不发任何额外命令（OPTS 探测在移动端会干扰数据通道，已废弃）
            connected = true
            config = cfg
            return true
        } catch (e: Exception) {
            lastError = explain(e)
            try { client.disconnect() } catch (_: Exception) {}
            connected = false
            return false
        }
    }

    /** 用上次的凭据自动重连 */
    suspend fun reconnect(): Boolean {
        val c = config ?: return false
        return connect(c)
    }

    /** 列出目录（path 传 "." 为根） */
    suspend fun listFiles(path: String = "."): List<FtpEntry> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (!connected) return@withContext emptyList()
                try {
                    val arr = client.listFiles(path) ?: return@withContext emptyList()
                    arr.map {
                        FtpEntry(it.name, it.isDirectory, it.size, it.timestamp.timeInMillis)
                    }.filter { it.name != "." && it.name != ".." }
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }
            }
        }

    /** 下载到本地缓存文件。发命令全在 try 内：僵尸 socket 抛异常只置失效、不外抛（不闪退）。 */
    suspend fun download(remotePath: String, localFile: File): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (!connected) return@withContext false
                localFile.parentFile?.mkdirs()
                try {
                    val stream = client.retrieveFileStream(remotePath)
                    if (stream == null) {
                        try { client.completePendingCommand() } catch (_: Exception) {}
                        return@withContext false
                    }
                    localFile.outputStream().use { out -> stream.copyTo(out) }
                    stream.close()
                    client.completePendingCommand()
                    true
                } catch (_: Exception) {
                    try { client.abort() } catch (_: Exception) {}
                    connected = false
                    false
                }
            }
        }

    /** 下载远端文件直接写入任意 OutputStream（SAF content URI 走这里，不落本地 File） */
    suspend fun downloadToStream(remotePath: String, out: OutputStream): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (!connected) return@withContext false
                try {
                    val stream = client.retrieveFileStream(remotePath)
                    if (stream == null) {
                        try { client.completePendingCommand() } catch (_: Exception) {}
                        return@withContext false
                    }
                    stream.copyTo(out)
                    stream.close()
                    out.flush()
                    client.completePendingCommand()
                    true
                } catch (_: Exception) {
                    try { client.abort() } catch (_: Exception) {}
                    connected = false
                    false
                }
            }
        }

    /** 从任意 InputStream 上传（SAF content URI 走这里）。发命令全在 try 内，僵尸 socket 不外抛。 */
    suspend fun uploadFromStream(input: InputStream, remotePath: String): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (!connected) return@withContext false
                try {
                    val out = client.storeFileStream(remotePath)
                    if (out == null) {
                        try { client.completePendingCommand() } catch (_: Exception) {}
                        return@withContext false
                    }
                    input.copyTo(out)
                    out.close()
                    client.completePendingCommand()
                    true
                } catch (_: Exception) {
                    try { client.abort() } catch (_: Exception) {}
                    connected = false
                    false
                }
            }
        }

    /** 上传本地文件到服务器（覆盖）。发命令全在 try 内，僵尸 socket 不外抛。 */
    suspend fun upload(localFile: File, remotePath: String): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (!connected) return@withContext false
                try {
                    val out = client.storeFileStream(remotePath)
                    if (out == null) {
                        try { client.completePendingCommand() } catch (_: Exception) {}
                        return@withContext false
                    }
                    localFile.inputStream().use { it.copyTo(out) }
                    out.close()
                    client.completePendingCommand()
                    true
                } catch (_: Exception) {
                    try { client.abort() } catch (_: Exception) {}
                    connected = false
                    false
                }
            }
        }

    fun getSavedConfig(): FtpConfig? = config

    /** 给远端文件末尾追加一行（小组件快速记录用）。
     *  只用 RETR + STOR 两条已验证可靠的命令，不引入 APPEND 模式：
     *    1) 先把旧内容下载到内存（文件不存在 → 当空，550/450 都算"从空开始"）
     *    2) 末尾拼 "\n<新行>"
     *    3) STOR 整体写回（覆盖）。
     *  文本行号小（快速记录场景），整文件读写在 100KB 量级无压力。 */
    suspend fun appendLine(remotePath: String, line: String): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (!connected) { lastError = "连接已断开"; return@withContext false }
                try {
                    // 1) 取旧内容（不存在则空）。必须经 InputStreamReader 显式 UTF-8，
                    //  绝不 readBytes 后无 charset 解码（平台默认 charset 会毁非 ASCII 内容）
                    var old = ""
                    val input = client.retrieveFileStream(remotePath)
                    if (input != null) {
                        try {
                            val rdr = java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)
                            val sb = StringBuilder()
                            val buf = CharArray(4096)
                            while (true) {
                                val n = rdr.read(buf)
                                if (n < 0) break
                                sb.append(buf, 0, n)
                            }
                            old = sb.toString()
                            rdr.close()
                            client.completePendingCommand()
                        } catch (_: Exception) {
                            try { client.abort() } catch (_: Exception) {}
                        }
                        input.close()
                    } else {
                        // 450/550：文件不存在（或服务器没给流）→ 当空文件
                        try { client.completePendingCommand() } catch (_: Exception) {}
                        old = ""
                    }
                    // 2) 拼接（保证前一行有换行）
                    val merged = if (old.isEmpty()) line else old.trimEnd('\n') + "\n" + line
                    // 3) 整体写回（显式 UTF-8）
                    val out = client.storeFileStream(remotePath)
                    if (out == null) {
                        try { client.completePendingCommand() } catch (_: Exception) {}
                        lastError = "写入被拒绝 ${replyText()}"
                        return@withContext false
                    }
                    out.write(merged.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                    out.close()
                    client.completePendingCommand()
                    lastError = ""
                    true
                } catch (e: Exception) {
                    try { client.abort() } catch (_: Exception) {}
                    connected = false
                    lastError = "追加失败：${e.javaClass.simpleName}"
                    false
                }
            }
        }

    /** 取最近一条服务器应答（code + 首行），用于把"被拒"的真实原因显示给用户 */
    private fun replyText(): String = try {
        val code = client.replyCode
        val s = client.replyString?.lineSequence()?.firstOrNull()?.trim() ?: ""
        if (code == 0 && s.isEmpty()) "(无应答)" else "($code $s)"
    } catch (_: Exception) { "(无应答)" }

    /** 删除远端文件（DELE，纯控制通道命令，无数据通道）。
     *  注意：commons-net 的 deleteFile 内部已读完 DELE 应答，**不要再 completePendingCommand**，
     *  否则会空等 20s 才返回（v1.11 的"删除无效/卡住"即此）。 */
    suspend fun deleteFile(remotePath: String): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (!connected) { lastError = "连接已断开"; return@withContext false }
                try {
                    val ok = client.deleteFile(remotePath)
                    lastError = if (ok) "" else "服务器拒绝删除 ${replyText()}"
                    ok
                } catch (e: Exception) {
                    try { client.abort() } catch (_: Exception) {}
                    connected = false
                    lastError = "删除失败：${e.javaClass.simpleName}"
                    false
                }
            }
        }

    /** 重命名 / 移动（REN：from→to 均为相对根的全路径；跨目录=to 写到目标目录全路径）。
     *  纯控制通道。同 deleteFile：不再 completePendingCommand；失败时抓服务器应答。 */
    suspend fun renameFile(from: String, to: String): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (!connected) { lastError = "连接已断开"; return@withContext false }
                try {
                    val ok = client.rename(from, to)
                    lastError = if (ok) "" else "服务器拒绝重命名/移动 ${replyText()}"
                    ok
                } catch (e: Exception) {
                    try { client.abort() } catch (_: Exception) {}
                    connected = false
                    lastError = "重命名/移动失败：${e.javaClass.simpleName}"
                    false
                }
            }
        }
}
