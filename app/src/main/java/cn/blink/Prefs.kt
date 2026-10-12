package cn.blink

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "blink_prefs"

    private const val SP_KEY_HOST = "cfg_host"
    private const val SP_KEY_PORT = "cfg_port"
    private const val SP_KEY_USER = "cfg_user"
    private const val SP_KEY_PASS = "cfg_pass"
    private const val SP_KEY_ENC  = "cfg_enc"
    private const val SP_LAST     = "last_remote_path"
    private const val SP_MIGRATED = "migrated_v2"
    private const val SP_NOTE_PATH  = "note_target_path"
    private const val SP_LAST_NOTE  = "last_note_text"      // 最新一条
    private const val SP_LAST_NOTE2 = "last_note_text_2"    // 次新
    private const val SP_LAST_NOTE3 = "last_note_text_3"    // 第三条
    private const val SP_LAST_DIR   = "last_browse_dir"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun saveConfig(ctx: Context, cfg: FtpConfig) {
        sp(ctx).edit()
            .putString(SP_KEY_HOST, cfg.host)
            .putInt(SP_KEY_PORT, cfg.port)
            .putString(SP_KEY_USER, cfg.user)
            .putString(SP_KEY_PASS, cfg.pass)
            .putString(SP_KEY_ENC, cfg.controlEncoding)
            .apply()
    }

    fun loadConfig(ctx: Context): FtpConfig? {
        val s = sp(ctx)
        val host = s.getString(SP_KEY_HOST, null) ?: return null
        // 一次性迁移：老版本默认勾了 GBK 写进了 Prefs，但服务器（ASUS 路由器）
        // 实际是 UTF-8 控制通道。第一次加载时把 GBK 纠正为 UTF-8，并记迁移标记。
        if (!s.getBoolean(SP_MIGRATED, false)) {
            val enc = s.getString(SP_KEY_ENC, "GBK") ?: "GBK"
            if (enc.equals("GBK", ignoreCase = true)) {
                s.edit()
                    .putString(SP_KEY_ENC, "UTF-8")
                    .putBoolean(SP_MIGRATED, true)
                    .apply()
            } else {
                s.edit().putBoolean(SP_MIGRATED, true).apply()
            }
        }
        val user: String = s.getString(SP_KEY_USER, "") ?: ""
        val pass: String = s.getString(SP_KEY_PASS, "") ?: ""
        val enc: String = s.getString(SP_KEY_ENC, "UTF-8") ?: "UTF-8"
        return FtpConfig(host, s.getInt(SP_KEY_PORT, 21), user, pass, enc)
    }

    fun saveLastFile(ctx: Context, remotePath: String) =
        sp(ctx).edit().putString(SP_LAST, remotePath).apply()

    fun loadLastFile(ctx: Context): String? =
        sp(ctx).getString(SP_LAST, null)

    // ── 小组件快速记录 ──────────────────────────────────────────

    /** 记住最后一次在 App 里浏览的 FTP 目录（列表加载成功时写） */
    fun saveLastBrowseDir(ctx: Context, dir: String) =
        sp(ctx).edit().putString(SP_LAST_DIR, dir).apply()

    fun loadLastBrowseDir(ctx: Context): String =
        sp(ctx).getString(SP_LAST_DIR, ".") ?: "."

    /** 快速记录追加到哪个 FTP 文件（相对根的全路径）。
     *  通用默认，不假设任何环境：
     *  ① 手动填过/记到FTP 存过的路径（最优先，各环境自适应）
     *  ② 最后在 App 里浏览到的目录（你浏览到哪就记到哪）
     *  ③ 都没有 → 根目录 quick-note.txt（中性默认，由用户第一次填/浏览修正） */
    fun loadNotePath(ctx: Context): String {
        val saved = sp(ctx).getString(SP_NOTE_PATH, null)
        if (!saved.isNullOrBlank()) return saved
        val dir = loadLastBrowseDir(ctx)
        return if (dir == ".") "quick-note.txt" else "$dir/quick-note.txt"
    }

    /** 用户是否已显式设定过记录目标（首次使用需设定一次，之后静默复用） */
    fun hasNoteSetting(ctx: Context): Boolean {
        val saved = sp(ctx).getString(SP_NOTE_PATH, null)
        return !saved.isNullOrBlank()
    }

    fun saveNotePath(ctx: Context, path: String) =
        sp(ctx).edit().putString(SP_NOTE_PATH, path.ifBlank { "quick-note.txt" }).apply()

    /** 记住最后三条记录（新记录顶入第1位，旧的下移，最旧丢弃）。
     *  直接沿用旧 SP_LAST_NOTE 里的值作为最早那条，无需迁移。 */
    fun saveLastNote(ctx: Context, text: String) {
        val s = sp(ctx)
        val n1 = s.getString(SP_LAST_NOTE, null)
        val n2 = s.getString(SP_LAST_NOTE2, null)
        s.edit()
            .putString(SP_LAST_NOTE, text)
            .putString(SP_LAST_NOTE2, n1 ?: "")
            .putString(SP_LAST_NOTE3, n2 ?: "")
            .apply()
    }

    /** 取最后三条（index 0=最新）。不足三条的槽位为空串 */
    fun loadLastNotes(ctx: Context): List<String> {
        val s = sp(ctx)
        return listOf(
            s.getString(SP_LAST_NOTE, "") ?: "",
            s.getString(SP_LAST_NOTE2, "") ?: "",
            s.getString(SP_LAST_NOTE3, "") ?: ""
        ).filter { it.isNotBlank() }
    }

    /** 单条（供小组件旧调用/兼容） */
    fun loadLastNote(ctx: Context): String? =
        sp(ctx).getString(SP_LAST_NOTE, null)

    fun clearConfig(ctx: Context) = sp(ctx).edit().clear().apply()
}
