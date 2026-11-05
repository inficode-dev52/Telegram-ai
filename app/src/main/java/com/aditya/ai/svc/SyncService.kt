package com.aditya.ai.svc
import android.app.*
import android.content.Intent
import android.os.IBinder
import com.aditya.ai.AdityaApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); private var job: Job? = null
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sync", "Telegram sync", NotificationManager.IMPORTANCE_LOW))
        fun note(t: String) = Notification.Builder(this, "sync").setContentTitle("ADITYA AI").setContentText(t).setOngoing(true).setSmallIcon(android.R.drawable.stat_notify_sync).build()
        startForeground(1, note("Starting…"))
        job?.cancel()
        val tg = (application as AdityaApp).tg
        job = scope.launch { combine(tg.connection, tg.online) { c, o -> if (o) "Telegram: $c" else "Offline, waiting for network" }.collect { nm.notify(1, note(it)) } }
        return START_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
