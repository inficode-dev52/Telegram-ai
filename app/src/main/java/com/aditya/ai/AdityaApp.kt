package com.aditya.ai
import android.app.Application
import com.aditya.ai.ai.*
import com.aditya.ai.data.Store
import com.aditya.ai.td.Telegram
import kotlinx.coroutines.flow.MutableStateFlow
class AdityaApp : Application() {
    lateinit var store: Store; lateinit var tg: Telegram; lateinit var ai: Ai; lateinit var engine: ReplyEngine; lateinit var brain: Intelligence
    val openChat = MutableStateFlow<Long?>(null); val theme = MutableStateFlow("system")
    @Volatile var visible = false; @Volatile var suppressLock = false
    override fun onCreate() {
        super.onCreate()
        store = Store(this); theme.value = store.str("theme", "system")
        tg = Telegram(this); ai = Ai(store); engine = ReplyEngine(store, tg, ai); brain = Intelligence(this, store, tg, ai)
        tg.start(); engine.start(); brain.start()
    }
}
