package com.aditya.ai.ui
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import com.aditya.ai.AdityaApp
import com.aditya.ai.td.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable fun Root(app: AdityaApp, locked: Boolean, unlock: () -> Unit) {
    val theme by app.theme.collectAsState(); val a by app.tg.auth.collectAsState()
    AdityaTheme(when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var splash by remember { mutableStateOf(true) }; LaunchedEffect(Unit) { delay(1500); splash = false }
            val key = when { locked -> "lock"; a == Auth.ERROR -> "error"; splash || a == Auth.LOADING -> "splash"; a == Auth.READY -> "main"; else -> "login" }
            Crossfade(key, animationSpec = tween(450), label = "root") { k -> when (k) {
                "lock" -> LockScreen(unlock); "error" -> ErrorScreen(app.tg); "splash" -> Splash(); "main" -> MainScreen(app); else -> LoginScreen(a, app.tg) } }
        }
    }
}
@Composable private fun ErrorScreen(tg: Telegram) {
    val err by tg.error.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(18.dp))
        Text("Connection setup failed", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            err ?: "Aditya could not initialize Telegram. Please reopen the app and try again.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
@Composable fun Logo(size: Dp) = Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(Lav, Color(0xFFB4A8F0)))), Alignment.Center) {
    Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(size / 2)) }
@Composable fun Splash() {
    val s = remember { Animatable(0.5f) }; val al = remember { Animatable(0f) }
    LaunchedEffect(Unit) { launch { s.animateTo(1f, spring(dampingRatio = 0.45f)) }; al.animateTo(1f, tween(800)) }
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.scale(s.value).alpha(al.value)) { Logo(92.dp) }; Spacer(Modifier.height(22.dp))
            Text("ADITYA AI", Modifier.alpha(al.value), style = MaterialTheme.typography.titleLarge, letterSpacing = 6.sp)
            Text("Telegram, thoughtfully assisted", Modifier.alpha(al.value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).width(120.dp).clip(CircleShape))
    }
}
@Composable fun LockScreen(unlock: () -> Unit) = Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
    Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)); Spacer(Modifier.height(16.dp))
    Text("ADITYA AI is locked", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(16.dp)); Button(unlock) { Text("Unlock") } }
@Composable fun LoginScreen(a: Auth, tg: Telegram) {
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(1f, 1.06f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pp")
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp).imePadding(), Arrangement.Center) {
        Box(Modifier.scale(pulse)) { Logo(64.dp) }; Spacer(Modifier.height(20.dp))
        AnimatedContent(a, transitionSpec = { (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut()) }, label = "step") { step -> Step(step, tg) }
    }
}
@Composable private fun Step(a: Auth, tg: Telegram) {
    var v by remember { mutableStateOf("") }; val busy by tg.busy.collectAsState(); val err by tg.error.collectAsState()
    val (title, sub, label) = when (a) {
        Auth.PHONE -> Triple("Welcome", "Sign in with your Telegram account.", "Mobile number, e.g. +14155550100")
        Auth.CODE -> Triple("Verification", "Enter the code Telegram sent you.", "Verification code")
        else -> Triple("Two-step verification", "Enter your Telegram password.", "Password") }
    fun go() { val t = v.trim(); if (t.isEmpty() || busy) return; when (a) { Auth.PHONE -> tg.submitPhone(t); Auth.CODE -> tg.submitCode(t); else -> tg.submitPassword(v) } }
    Column {
        Text(title, style = MaterialTheme.typography.headlineMedium); Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(24.dp))
        OutlinedTextField(v, { v = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = when (a) { Auth.PHONE -> KeyboardType.Phone; Auth.CODE -> KeyboardType.Number; else -> KeyboardType.Password }),
            visualTransformation = if (a == Auth.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None)
        AnimatedVisibility(err != null) { Text(err ?: "", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(20.dp))
        Button(::go, Modifier.fillMaxWidth().height(52.dp), enabled = !busy) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text("Continue") }
    }
}
@Composable fun MainScreen(app: AdityaApp) {
    var tab by remember { mutableIntStateOf(0) }; var chat by remember { mutableStateOf<Long?>(null) }; var overlay by remember { mutableStateOf<String?>(null) }
    val req by app.openChat.collectAsState(); LaunchedEffect(req) { req?.let { chat = it; app.openChat.value = null } }
    BackHandler(chat != null || overlay != null) { if (chat != null) chat = null else overlay = null }
    val tabs = listOf("Home" to Icons.Default.Home, "Chats" to Icons.Default.Chat, "Brain" to Icons.Default.Psychology, "Activity" to Icons.Default.Timeline, "Settings" to Icons.Default.Settings)
    Box(Modifier.fillMaxSize()) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
            tabs.forEachIndexed { i, (n, ic) -> NavigationBarItem(tab == i, { tab = i }, { Icon(ic, n) }, label = { Text(n, style = MaterialTheme.typography.labelSmall) }) } } }) { pad ->
            AnimatedContent(tab, Modifier.padding(pad).fillMaxSize(), transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "tab") { t -> when (t) {
                0 -> HomeScreen(app, { chat = it }) { overlay = it }; 1 -> ChatsScreen(app, { chat = it }) { overlay = it }
                2 -> BrainScreen(app) { chat = it }; 3 -> ActivityScreen(app, "all"); else -> SettingsScreen(app) } }
        }
        AnimatedVisibility(overlay != null, enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut()) {
            Surface(Modifier.fillMaxSize()) { when (overlay) { "search" -> SearchScreen(app, { overlay = null }) { overlay = null; chat = it }; else -> Column { Row(Modifier.padding(8.dp)) { IconButton({ overlay = null }) { Icon(Icons.Default.ArrowBack, "Back") } }; ActivityScreen(app, "notify") } } } }
        AnimatedVisibility(chat != null, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
            Surface(Modifier.fillMaxSize()) { chat?.let { ChatScreen(app, it) { chat = null } } } }
    }
}
