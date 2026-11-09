package com.aditya.ai
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import com.aditya.ai.ai.*
import com.aditya.ai.data.Store
import com.aditya.ai.data.V2Store
import com.aditya.ai.intel.IntelEngine
import com.aditya.ai.svc.Reminders
import com.aditya.ai.td.Telegram
import com.aditya.ai.voice.VoiceIO
import kotlinx.coroutines.flow.MutableStateFlow
class AdityaApp : Application() {
    lateinit var store: Store; lateinit var tg: Telegram; lateinit var ai: Ai; lateinit var engine: ReplyEngine; lateinit var brain: Intelligence
    // v2 additions (own storage file; the original store/TDLib objects above are created exactly as before)
    lateinit var v2: V2Store; lateinit var intel: IntelEngine; lateinit var voice: VoiceIO; lateinit var assistant: AssistantEngine
    val openChat = MutableStateFlow<Long?>(null); val theme = MutableStateFlow("system"); val reduceMotion = MutableStateFlow(false)
    @Volatile var visible = false; @Volatile var suppressLock = false
    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        store = Store(this); theme.value = store.str("theme", "system")
        v2 = V2Store(this); reduceMotion.value = v2.bool("reduce_motion", false)
        tg = Telegram(this); ai = Ai(store); engine = ReplyEngine(store, tg, ai, v2.drafts); brain = Intelligence(this, store, tg, ai)
        intel = IntelEngine(v2, tg, ai); voice = VoiceIO(this, v2)
        assistant = AssistantEngine(v2, store.events, store.tasks, tg, ai, intel, voice)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(Reminders.CHANNEL, "Task reminders", NotificationManager.IMPORTANCE_DEFAULT))
        tg.start(); engine.start(); brain.start(); intel.start()
    }
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val report = "Thread: ${thread.name}\n\n${sw}"
                openFileOutput("last_crash.txt", MODE_PRIVATE).use { it.write(report.toByteArray(Charsets.UTF_8)) }
                val intent = Intent(this, CrashReportActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                Thread.sleep(700)
            } catch (_: Throwable) { }
            Process.killProcess(Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }
}
