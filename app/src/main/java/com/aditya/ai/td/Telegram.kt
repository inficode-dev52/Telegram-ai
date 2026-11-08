package com.aditya.ai.td
import android.content.Context
import android.net.*
import android.os.*
import android.util.Log
import java.io.File
import com.aditya.ai.BuildConfig
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

enum class Auth { LOADING, PHONE, CODE, PASSWORD, READY, ERROR }
data class ChatItem(val id: Long, val title: String, val lastText: String, val unread: Int, val pinned: Boolean, val muted: Boolean,
                    val photoId: Int, val archived: Boolean, val kind: String, val lastDate: Int)
data class Msg(val id: Long, val chatId: Long, val text: String, val out: Boolean, val date: Int, val status: String, val kind: String,
               val fileId: Int, val replyTo: Long, val edited: Boolean, val sender: String)
data class Incoming(val chatId: Long, val msgId: Long, val text: String, val senderName: String, val chatTitle: String,
                    val chatKind: String, val isText: Boolean, val muted: Boolean)

class Telegram(private val ctx: Context) {
    private var client: Client? = null
    val auth = MutableStateFlow(Auth.LOADING); val busy = MutableStateFlow(false); val error = MutableStateFlow<String?>(null)
    val chats = MutableStateFlow<List<ChatItem>>(emptyList()); val loaded = MutableStateFlow(false)
    val connection = MutableStateFlow("Connecting…"); val online = MutableStateFlow(true)
    val messages = MutableStateFlow<List<Msg>>(emptyList()); val files = MutableStateFlow<Map<Int, String>>(emptyMap())
    val incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 64); val sentByMe = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    @Volatile var openId = 0L; @Volatile var meId = 0L
    private val chatMap = ConcurrentHashMap<Long, TdApi.Chat>(); private val users = ConcurrentHashMap<Long, TdApi.User>()
    private val cache = ConcurrentHashMap<Long, TreeMap<Long, TdApi.Message>>(); private val requested = ConcurrentHashMap.newKeySet<Int>()
    private val handler = Handler(Looper.getMainLooper()); private val pubPending = AtomicBoolean(false); private var warmed = false

    fun start() {
        client = Client.create(
            { onUpdate(it) },
            { e ->
                Log.e("AdityaTDLib", "TDLib update exception", e)
                error.value = e.message ?: "TDLib update error"
                auth.value = Auth.ERROR
            },
            { e ->
                Log.e("AdityaTDLib", "TDLib default exception", e)
                error.value = e.message ?: "TDLib initialization error"
                auth.value = Auth.ERROR
            }
        )
        runCatching {
            ctx.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { online.value = true; send(TdApi.SetNetworkType(TdApi.NetworkTypeOther()), true) }
                override fun onLost(n: Network) { online.value = false; send(TdApi.SetNetworkType(TdApi.NetworkTypeNone()), true) } })
        }
    }
    private fun send(f: TdApi.Function<*>, silent: Boolean = false, done: (TdApi.Object) -> Unit = {}) {
        val c = client ?: return
        c.send(f) { r -> if (r is TdApi.Error) { if (!silent) error.value = r.message } else done(r) }
    }
    private fun authCall(f: TdApi.Function<*>) { busy.value = true; error.value = null
        client?.send(f) { r -> busy.value = false; if (r is TdApi.Error) error.value = r.message } }

    private fun onUpdate(o: TdApi.Object) {
        when (o) {
            is TdApi.UpdateAuthorizationState -> handleAuth(o.authorizationState)
            is TdApi.UpdateConnectionState -> connection.value = when (o.state) {
                is TdApi.ConnectionStateReady -> "Connected"; is TdApi.ConnectionStateWaitingForNetwork -> "Waiting for network"
                is TdApi.ConnectionStateUpdating -> "Updating…"; else -> "Connecting…" }
            is TdApi.UpdateUser -> users[o.user.id] = o.user
            is TdApi.UpdateNewChat -> { chatMap[o.chat.id] = o.chat; notePhoto(o.chat); publish() }
            is TdApi.UpdateChatLastMessage -> chatMap[o.chatId]?.let { it.lastMessage = o.lastMessage; if (o.positions.isNotEmpty()) it.positions = o.positions; publish() }
            is TdApi.UpdateChatPosition -> chatMap[o.chatId]?.let { c -> synchronized(c) {
                c.positions = (c.positions.filter { it.list.constructor != o.position.list.constructor } + o.position).filter { it.order != 0L }.toTypedArray() }; publish() }
            is TdApi.UpdateChatReadInbox -> chatMap[o.chatId]?.let { it.unreadCount = o.unreadCount; publish() }
            is TdApi.UpdateChatReadOutbox -> chatMap[o.chatId]?.let { it.lastReadOutboxMessageId = o.lastReadOutboxMessageId; if (o.chatId == openId) publishOpen() }
            is TdApi.UpdateChatTitle -> chatMap[o.chatId]?.let { it.title = o.title; publish() }
            is TdApi.UpdateChatPhoto -> chatMap[o.chatId]?.let { it.photo = o.photo; notePhoto(it); publish() }
            is TdApi.UpdateChatNotificationSettings -> chatMap[o.chatId]?.let { it.notificationSettings = o.notificationSettings; publish() }
            is TdApi.UpdateFile -> if (o.file.local.isDownloadingCompleted) files.value = files.value + (o.file.id to o.file.local.path)
            is TdApi.UpdateNewMessage -> onNewMessage(o.message)
            is TdApi.UpdateMessageContent -> cache[o.chatId]?.let { t -> synchronized(t) { t[o.messageId]?.content = o.newContent }; if (o.chatId == openId) publishOpen() }
            is TdApi.UpdateMessageEdited -> cache[o.chatId]?.let { t -> synchronized(t) { t[o.messageId]?.editDate = o.editDate }; if (o.chatId == openId) publishOpen() }
            is TdApi.UpdateDeleteMessages -> if (o.isPermanent) cache[o.chatId]?.let { t -> synchronized(t) { o.messageIds.forEach { t.remove(it) } }; if (o.chatId == openId) publishOpen() }
            is TdApi.UpdateMessageSendSucceeded -> { cache[o.message.chatId]?.let { t -> synchronized(t) { t.remove(o.oldMessageId) } }; cacheAdd(o.message); if (o.message.chatId == openId) publishOpen() }
            is TdApi.UpdateMessageSendFailed -> { cache[o.message.chatId]?.let { t -> synchronized(t) { t.remove(o.oldMessageId) } }; cacheAdd(o.message); if (o.message.chatId == openId) publishOpen() }
        }
    }
    private fun onNewMessage(m: TdApi.Message) {
        cacheAdd(m); if (m.chatId == openId) publishOpen()
        if (m.isOutgoing) { sentByMe.tryEmit(m.chatId); return }
        val c = chatMap[m.chatId]; val txt = (m.content as? TdApi.MessageText)?.text?.text
        incoming.tryEmit(Incoming(m.chatId, m.id, txt ?: preview(m.content), senderName(m), c?.title ?: "", c?.let { item(it).kind } ?: "Private", txt != null, (c?.notificationSettings?.muteFor ?: 0) > 0))
    }
    private fun handleAuth(s: TdApi.AuthorizationState) {
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val p = TdApi.SetTdlibParameters()
                val dbDir = File(ctx.filesDir, "td")
                val filesDir = File(ctx.filesDir, "tdfiles")
                check(dbDir.exists() || dbDir.mkdirs()) { "Cannot create TDLib database directory" }
                check(filesDir.exists() || filesDir.mkdirs()) { "Cannot create TDLib files directory" }
                p.databaseDirectory = dbDir.absolutePath
                p.filesDirectory = filesDir.absolutePath
                p.useMessageDatabase = true; p.useChatInfoDatabase = true; p.useFileDatabase = true
                p.apiId = BuildConfig.TG_API_ID; p.apiHash = BuildConfig.TG_API_HASH
                p.systemLanguageCode = "en"; p.deviceModel = "Android"; p.applicationVersion = BuildConfig.VERSION_NAME
                client?.send(p) { r ->
                    if (r is TdApi.Error) {
                        val message = "TDLib setup failed (${r.code}): ${r.message}"
                        Log.e("AdityaTDLib", message)
                        error.value = message
                        auth.value = Auth.ERROR
                    }
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> auth.value = Auth.PHONE
            is TdApi.AuthorizationStateWaitCode -> auth.value = Auth.CODE
            is TdApi.AuthorizationStateWaitPassword -> auth.value = Auth.PASSWORD
            is TdApi.AuthorizationStateReady -> { error.value = null; auth.value = Auth.READY
                send(TdApi.GetMe(), true) { meId = (it as? TdApi.User)?.id ?: 0L }; loadChats() }
            is TdApi.AuthorizationStateClosed -> { client = null; chatMap.clear(); cache.clear(); chats.value = emptyList(); loaded.value = false; auth.value = Auth.LOADING; start() }
            else -> {}
        }
    }
    fun submitPhone(n: String) = authCall(TdApi.SetAuthenticationPhoneNumber(n, null))
    fun submitCode(c: String) = authCall(TdApi.CheckAuthenticationCode(c))
    fun submitPassword(p: String) = authCall(TdApi.CheckAuthenticationPassword(p))
    fun logout() = send(TdApi.LogOut())

    fun loadChats() {
        send(TdApi.LoadChats(TdApi.ChatListMain(), 200), true) { loaded.value = true }
        send(TdApi.LoadChats(TdApi.ChatListArchive(), 100), true)
        handler.postDelayed({ loaded.value = true }, 4000)
    }
    private fun notePhoto(c: TdApi.Chat) { c.photo?.small?.let { f -> if (f.local.isDownloadingCompleted) files.value = files.value + (f.id to f.local.path) } }
    fun ensureFile(id: Int) {
        if (id == 0 || files.value.containsKey(id) || !requested.add(id)) return
        send(TdApi.DownloadFile(id, 16, 0, 0, false), true) { r -> (r as? TdApi.File)?.let { if (it.local.isDownloadingCompleted) files.value = files.value + (it.id to it.local.path) } }
    }
    private fun preview(c: TdApi.MessageContent) = when (c) {
        is TdApi.MessageText -> c.text.text; is TdApi.MessagePhoto -> "Photo"; is TdApi.MessageDocument -> "Document"
        is TdApi.MessageVideo -> "Video"; is TdApi.MessageVoiceNote -> "Voice message"; is TdApi.MessageSticker -> "Sticker"; else -> "Message" }

    private fun item(c: TdApi.Chat): ChatItem {
        val main = c.positions.firstOrNull { it.list is TdApi.ChatListMain }; val arch = c.positions.firstOrNull { it.list is TdApi.ChatListArchive }
        val kind = when (val t = c.type) {
            is TdApi.ChatTypePrivate -> when { t.userId == meId -> "Saved"; users[t.userId]?.type is TdApi.UserTypeBot -> "Bot"; else -> "Private" }
            is TdApi.ChatTypeSupergroup -> if (t.isChannel) "Channel" else "Group"; else -> "Group" }
        val title = if (kind == "Saved") "Saved Messages" else c.title
        return ChatItem(c.id, title, c.lastMessage?.let { preview(it.content) } ?: "", c.unreadCount, main?.isPinned == true,
            c.notificationSettings.muteFor > 0, c.photo?.small?.id ?: 0, main == null && arch != null, kind, c.lastMessage?.date ?: 0)
    }
    private fun publish() {
        if (!pubPending.getAndSet(true)) handler.postDelayed({ pubPending.set(false)
            chats.value = chatMap.values.filter { c -> c.positions.any { it.list is TdApi.ChatListMain || it.list is TdApi.ChatListArchive } }
                .sortedByDescending { c -> c.positions.maxOfOrNull { it.order } ?: 0L }.map { item(it) } }, 150)
    }
    private fun senderName(m: TdApi.Message): String = when (val s = m.senderId) {
        is TdApi.MessageSenderUser -> users[s.userId]?.let { "${it.firstName} ${it.lastName}".trim() } ?: chatMap[m.chatId]?.title ?: ""
        is TdApi.MessageSenderChat -> chatMap[s.chatId]?.title ?: ""; else -> "" }
    private fun map(m: TdApi.Message): Msg {
        var text = ""; var kind = "text"; var fid = 0
        when (val c = m.content) {
            is TdApi.MessageText -> text = c.text.text
            is TdApi.MessagePhoto -> { kind = "photo"; text = c.caption.text; fid = (c.photo.sizes.firstOrNull { it.width >= 320 } ?: c.photo.sizes.lastOrNull())?.photo?.id ?: 0 }
            is TdApi.MessageDocument -> { kind = "doc"; text = c.document.fileName + if (c.caption.text.isNotEmpty()) "\n" + c.caption.text else ""; fid = c.document.document.id }
            is TdApi.MessageVideo -> { kind = "other"; text = "Video" }
            else -> { kind = "other"; text = preview(c) }
        }
        val status = when {
            !m.isOutgoing -> ""; m.sendingState is TdApi.MessageSendingStateFailed -> "failed"; m.sendingState != null -> "sending"
            m.id <= (chatMap[m.chatId]?.lastReadOutboxMessageId ?: 0L) -> "read"; else -> "sent" }
        return Msg(m.id, m.chatId, text, m.isOutgoing, m.date, status, kind, fid, (m.replyTo as? TdApi.MessageReplyToMessage)?.messageId ?: 0L, m.editDate > 0, if (m.isOutgoing) "You" else senderName(m))
    }
    private fun cacheAdd(m: TdApi.Message) { val t = cache.getOrPut(m.chatId) { TreeMap() }; synchronized(t) { t[m.id] = m } }
    private fun publishOpen() { messages.value = cache[openId]?.let { t -> synchronized(t) { t.values.map { map(it) } } } ?: emptyList() }

    private fun requestHistory(chatId: Long, from: Long, limit: Int, cb: (Int) -> Unit) {
        val c = client ?: return cb(0)
        c.send(TdApi.GetChatHistory(chatId, from, 0, limit, false)) { r ->
            if (r is TdApi.Messages) { r.messages.filterNotNull().forEach { cacheAdd(it) }; if (chatId == openId) publishOpen(); cb(r.messages.size) } else cb(0) }
    }
    fun openChat(id: Long) {
        openId = id; error.value = null; send(TdApi.OpenChat(id), true); publishOpen()
        requestHistory(id, 0, 50) { markRead(id) }
    }
    fun closeChat() { if (openId != 0L) send(TdApi.CloseChat(openId), true); openId = 0; messages.value = emptyList() }
    fun loadOlder() { val t = cache[openId]; val from = t?.let { synchronized(it) { if (it.isEmpty()) 0L else it.firstKey() } } ?: 0L; requestHistory(openId, from, 40) {} }
    private fun markRead(id: Long) {
        val ids = cache[id]?.let { t -> synchronized(t) { t.values.filter { !it.isOutgoing }.takeLast(30).map { it.id }.toLongArray() } } ?: return
        if (ids.isEmpty()) return
        val v = TdApi.ViewMessages(); v.chatId = id; v.messageIds = ids; v.forceRead = true; send(v, true)
    }
    suspend fun fetchHistory(chatId: Long, n: Int): List<Msg> {
        for (i in 0 until 3) {
            val t = cache[chatId]; val have = t?.let { synchronized(it) { it.size } } ?: 0; if (have >= n) break
            val from = t?.let { synchronized(it) { if (it.isEmpty()) 0L else it.firstKey() } } ?: 0L
            val got = suspendCancellableCoroutine<Int> { c -> requestHistory(chatId, from, n) { c.resume(it) } }
            if (got == 0) break
        }
        return cache[chatId]?.let { t -> synchronized(t) { t.values.map { map(it) } } }?.takeLast(n) ?: emptyList()
    }
    suspend fun warmCache() { if (warmed) return; warmed = true; chats.value.take(30).forEach { fetchHistory(it.id, 60) } }
    fun searchMessages(q: String): List<Msg> {
        val k = q.lowercase(); val out = ArrayList<Msg>()
        for (t in cache.values) synchronized(t) { for (m in t.values) {
            val s = when (val c = m.content) { is TdApi.MessageText -> c.text.text; is TdApi.MessagePhoto -> c.caption.text; is TdApi.MessageDocument -> c.document.fileName + c.caption.text; else -> "" }
            if (s.lowercase().contains(k)) out.add(map(m)) } }
        return out.sortedByDescending { it.date }.take(50)
    }

    private fun textContent(t: String): TdApi.InputMessageText { val c = TdApi.InputMessageText(); c.text = TdApi.FormattedText(t, arrayOf()); return c }
    private fun reply(s: TdApi.SendMessage, to: Long) { if (to != 0L) { val r = TdApi.InputMessageReplyToMessage(); r.messageId = to; s.replyTo = r } }
    fun sendText(chatId: Long, text: String, replyTo: Long = 0) { val s = TdApi.SendMessage(); s.chatId = chatId; reply(s, replyTo); s.inputMessageContent = textContent(text); send(s) }
    fun sendFile(chatId: Long, path: String, photo: Boolean, caption: String, replyTo: Long = 0) {
        val s = TdApi.SendMessage()
        s.chatId = chatId
        reply(s, replyTo)
        val cap = TdApi.FormattedText(caption, arrayOf())

        if (photo) {
            val input = TdApi.InputPhoto()
            input.photo = TdApi.InputFileLocal(path)
            val p = TdApi.InputMessagePhoto()
            p.photo = input
            p.caption = cap
            s.inputMessageContent = p
        } else {
            val input = TdApi.InputDocument()
            input.document = TdApi.InputFileLocal(path)
            val d = TdApi.InputMessageDocument()
            d.document = input
            d.caption = cap
            s.inputMessageContent = d
        }
        send(s)
    }

    fun edit(chatId: Long, msgId: Long, text: String) { val e = TdApi.EditMessageText(); e.chatId = chatId; e.messageId = msgId; e.inputMessageContent = textContent(text); send(e) }
    fun delete(chatId: Long, msgId: Long) {
        send(TdApi.DeleteMessages(chatId, longArrayOf(msgId), false))
    }

    fun forward(toChat: Long, fromChat: Long, msgId: Long) { val f = TdApi.ForwardMessages(); f.chatId = toChat; f.fromChatId = fromChat; f.messageIds = longArrayOf(msgId); send(f) }
}
