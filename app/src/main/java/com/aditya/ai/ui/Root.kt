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
    val theme by app.theme.collectAsState(); val a by app.tg.auth.collectAsState(); val reducePref by app.reduceMotion.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val systemOff = remember { runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
    AdityaTheme(when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }) {
        CompositionLocalProvider(LocalReduceMotion provides (reducePref || systemOff)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                // Full intro on the first launch, a short one afterwards (user can change this in Settings). Auth logic below is unchanged.
                val seen = remember { app.v2.bool("intro_seen", false) }
                val introMode = remember { when (app.v2.str("intro_mode", "auto")) { "off" -> IntroMode.OFF; "short" -> IntroMode.SHORT; "full" -> IntroMode.FULL; else -> if (seen) IntroMode.SHORT else IntroMode.FULL } }
                var splash by remember { mutableStateOf(true) }
                val key = when { locked -> "lock"; a == Auth.ERROR -> "error"; splash -> "intro"; a == Auth.LOADING -> "splash"; a == Auth.READY -> "main"; else -> "login" }
                Crossfade(key, animationSpec = tween(450), label = "root") { k -> when (k) {
                    "lock" -> LockScreen(unlock); "error" -> ErrorScreen(app.tg)
                    "intro" -> StartupIntro(introMode) { app.v2.setBool("intro_seen", true); splash = false }
                    "splash" -> Splash(); "main" -> MainScreen(app); else -> LoginScreen(a, app.tg) } }
            }
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
@Composable fun Logo(size: Dp) = AdityaMark(size)
@Composable fun Splash() {
    Box(Modifier.fillMaxSize(), Alignment.Center) { BrandLockup()
        LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).width(120.dp).clip(CircleShape)) }
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
    var tab by remember { mutableIntStateOf(0) }; var seg by remember { mutableIntStateOf(0) }; var chat by remember { mutableStateOf<Long?>(null) }; var overlay by remember { mutableStateOf<String?>(null) }
    val req by app.openChat.collectAsState(); LaunchedEffect(req) { req?.let { chat = it; app.openChat.value = null } }
    BackHandler(chat != null || overlay != null) { if (chat != null) chat = null else overlay = null }
    val tabs = listOf("Home" to Icons.Default.Home, "Chats" to Icons.Default.Chat, "Aditya" to Icons.Default.AutoAwesome, "Intelligence" to Icons.Default.Psychology, "Settings" to Icons.Default.Settings)
    val nav: (Int, Int) -> Unit = { t, s -> tab = t; seg = s }
    Box(Modifier.fillMaxSize()) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
            tabs.forEachIndexed { i, (n, ic) -> NavigationBarItem(tab == i, { tab = i; seg = 0 }, { Icon(ic, n) }, label = { Text(n, style = MaterialTheme.typography.labelSmall, maxLines = 1) }) } } }) { pad ->
            AnimatedContent(tab, Modifier.padding(pad).fillMaxSize(), transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "tab") { t -> when (t) {
                0 -> CommandCenter(app, { chat = it }, { overlay = it }, nav); 1 -> ChatsScreen(app, { chat = it }) { overlay = it }
                2 -> AssistantScreen(app); 3 -> IntelligenceScreen(app, seg) { chat = it }; else -> SettingsScreen(app) } }
        }
        AnimatedVisibility(overlay != null, enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut()) {
            Surface(Modifier.fillMaxSize()) { when (overlay) { "search" -> SearchScreen(app, { overlay = null }) { overlay = null; chat = it }; else -> Column { Row(Modifier.padding(8.dp)) { IconButton({ overlay = null }) { Icon(Icons.Default.ArrowBack, "Back") } }; ActivityScreen(app, "notify") } } } }
        AnimatedVisibility(chat != null, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
            Surface(Modifier.fillMaxSize()) { chat?.let { ChatScreen(app, it) { chat = null } } } }
    }
}
