package com.aditya.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aditya.ai.AdityaApp
import com.aditya.ai.data.*
import com.aditya.ai.intel.BriefingBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/** Home / Command Center. Every number comes from Telegram chat state or stored detections; nothing is estimated. */
@Composable fun CommandCenter(app: AdityaApp, open: (Long) -> Unit, go: (String) -> Unit, nav: (Int, Int) -> Unit) {
    val tg = app.tg; val scope = rememberCoroutineScope()
    val chats by tg.chats.collectAsStateWithLifecycle(); val loaded by tg.loaded.collectAsStateWithLifecycle()
    val conn by tg.connection.collectAsStateWithLifecycle(); val online by tg.online.collectAsStateWithLifecycle()
    val insights by app.v2.insights.collectAsStateWithLifecycle(); val tasks by app.v2.tasks.collectAsStateWithLifecycle()
    val legacy by app.store.tasks.collectAsStateWithLifecycle(); val intelOn by app.intel.enabled.collectAsStateWithLifecycle()
    val scanning by app.intel.scanning.collectAsStateWithLifecycle(); val orb by app.assistant.orb.collectAsStateWithLifecycle()
    val unread = chats.filter { !it.archived && !it.muted }.sumOf { it.unread }
    val ov = remember(insights, tasks, legacy, unread) { BriefingBuilder.overview(insights, tasks, legacy.count { !it.done }, unread, System.currentTimeMillis()) }
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when { hour < 12 -> "Good morning"; hour < 18 -> "Good afternoon"; else -> "Good evening" }
    val alerts = BriefingBuilder.active(insights).count { it.priority == Priority.HIGH }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AdityaMark(40.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("ADITYA AI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 3.sp)
                Text(greeting, style = MaterialTheme.typography.headlineSmall)
                Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton({ go("search") }) { Icon(Icons.Default.Search, "Search") }
            IconButton({ go("notifs") }) { BadgedBox({ if (alerts > 0) Badge { Text("$alerts") } }) { Icon(Icons.Default.Notifications, "Alerts") } }
        }
        Pill(if (online) conn else "Offline", online && conn == "Connected")
        if (!online) StateCard(Icons.Default.WifiOff, "You're offline", "Showing the last data Aditya has. New messages will sync when you're back online.", tint = Ax.Warn)

        Column(Modifier.fillMaxWidth().clickable { nav(2, 0) }, horizontalAlignment = Alignment.CenterHorizontally) {
            AiOrb(orb, size = 220.dp); Text(orbLabel(orb), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }

        Section("Intelligence overview")
        if (!loaded && chats.isEmpty()) { repeat(3) { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Box(Modifier.weight(1f).height(84.dp).shimmer()); Box(Modifier.weight(1f).height(84.dp).shimmer()) } } }
        else {
            fun v(n: Int): Int? = if (intelOn) n else null
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("Important", v(ov.important), Icons.Default.PriorityHigh, Modifier.weight(1f)) { nav(3, 0) }
                Metric("Unread", ov.unread, Icons.Default.MarkChatUnread, Modifier.weight(1f)) { nav(1, 0) } }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("Pending replies", v(ov.pendingReplies), Icons.Default.Reply, Modifier.weight(1f)) { nav(3, 3) }
                Metric("Action required", v(ov.actionRequired), Icons.Default.Bolt, Modifier.weight(1f)) { nav(3, 0) } }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("Upcoming events", v(ov.upcoming), Icons.Default.Event, Modifier.weight(1f)) { nav(3, 1) }
                Metric("Pending tasks", ov.pendingTasks, Icons.Default.TaskAlt, Modifier.weight(1f)) { nav(3, 2) } }
            if (!intelOn) StateCard(Icons.Default.Shield, "Message intelligence is off",
                "Turn it on to detect important messages, replies you owe and dates. Analysis runs on this device; AI classification is a separate opt-in.",
                actionLabel = "Turn on and scan", onAction = { app.intel.setEnabled(true); scope.launch { app.intel.scan() } })
            else if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        Section("Quick actions")
        Action("Generate daily briefing", Icons.Default.WbSunny) { if (intelOn) scope.launch { app.intel.scan() }; nav(3, 1) }
        Action("Summarize unread messages", Icons.Default.Summarize) { app.assistant.ask("Summarize my unread messages"); nav(2, 0) }
        Action("Find important conversations", Icons.Default.Star) { nav(3, 0) }
        Action("Review pending replies", Icons.Default.Reply) { nav(3, 3) }
        Action("Open AI assistant", Icons.Default.AutoAwesome) { nav(2, 0) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable private fun Metric(label: String, value: Int?, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier, onClick) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        if (value != null) AnimatedCount(value, MaterialTheme.typography.displaySmall, MaterialTheme.colorScheme.onSurface) else Text("Off", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun Action(label: String, icon: ImageVector, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp))
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
