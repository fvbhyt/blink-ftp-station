package cn.blink

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * 桌面小组件「Blink 快速记录」：
 *  - 卡片显示最后一条记录（本地 Prefs，加载零网络）
 *  - 点卡片 → NoteDialogActivity（输入框 + 目标路径）→ 追加写入 FTP
 *  记完通过 QuickNoteWidget.refreshAll 即时刷新预览。
 */
class QuickNoteWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_NOTE_CHANGED = "cn.blink.action.NOTE_CHANGED"

        /** 刷新所有实例（NoteDialogActivity 记完调用） */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            mgr.getAppWidgetIds(ComponentName(context, QuickNoteWidget::class.java))
                .forEach { buildIt(context, mgr, it) }
        }

        /** 构建并推送单个实例视图：显示最后三条（最新在上），蓝底白字 */
        private fun buildIt(context: Context, mgr: AppWidgetManager, id: Int) {
            val rv = RemoteViews(context.packageName, R.layout.widget_quick_note)
            val notes = Prefs.loadLastNotes(context)   // index 0 = 最新
            rv.setTextViewText(
                R.id.widget_note_1,
                notes.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "点卡片记第一条"
            )
            rv.setTextViewText(R.id.widget_note_2, notes.getOrNull(1) ?: "")
            rv.setTextViewText(R.id.widget_note_3, notes.getOrNull(2) ?: "")
            // 空槽位隐藏，卡片紧凑
            rv.setViewVisibility(
                R.id.widget_note_2,
                if (notes.getOrNull(1).isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            )
            rv.setViewVisibility(
                R.id.widget_note_3,
                if (notes.getOrNull(2).isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            )
            val pi = PendingIntent.getActivity(
                context, id,
                Intent(context, NoteDialogActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_root, pi)
            mgr.updateAppWidget(id, rv)
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { buildIt(context, mgr, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_NOTE_CHANGED) refreshAll(context)
        super.onReceive(context, intent)
    }
}
