package com.aditya.ai

import com.aditya.ai.ai.*
import com.aditya.ai.data.*
import com.aditya.ai.intel.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class DetectorTest {
    @Test fun urgentRequestInPrivateChat() {
        val d = Detector.classify("Please send the report ASAP", true)!!
        assertTrue(Category.URGENT in d.categories); assertTrue(Category.REQUEST in d.categories); assertTrue(Category.REPLY in d.categories)
        assertEquals(Priority.HIGH, d.priority); assertTrue(d.reason.contains("asap", ignoreCase = true))
    }
    @Test fun plainChatterIsNotFlagged() = assertNull(Detector.classify("lol nice one", true))
    @Test fun questionOnlyCountsAsReplyInPrivateChat() {
        assertTrue(Category.REPLY in Detector.classify("are you coming?", true)!!.categories)
        assertNull(Detector.classify("are you coming?", false))
    }
    @Test fun dueToIsNotADeadline() = assertNull(Detector.classify("running late due to traffic", false))
    @Test fun isoDateIsExplicitAndConfident() {
        val p = Detector.parseDate("Submit by 2026-11-20 17:30", System.currentTimeMillis())!!
        val c = Calendar.getInstance().apply { timeInMillis = p.dueMs }
        assertEquals(2026, c.get(Calendar.YEAR)); assertEquals(10, c.get(Calendar.MONTH)); assertEquals(20, c.get(Calendar.DAY_OF_MONTH)); assertEquals(17, c.get(Calendar.HOUR_OF_DAY))
        assertEquals("", p.uncertain)
    }
    @Test fun relativeDateIsFlaggedUncertain() {
        val p = Detector.parseDate("meeting tomorrow at 5pm", System.currentTimeMillis())!!
        assertTrue(p.dueMs > 0); assertTrue(p.uncertain.isNotEmpty()); assertEquals("tomorrow", p.text)
    }
    @Test fun ambiguousNumericDateIsNotInvented() {
        val p = Detector.parseDate("due 03/04/2026", System.currentTimeMillis())!!
        assertEquals(0L, p.dueMs); assertTrue(p.uncertain.isNotEmpty())
    }
    @Test fun noDateMeansNull() = assertNull(Detector.parseDate("see you soon", System.currentTimeMillis()))
    @Test fun answeredQuestionsLoseReplyFlag() {
        fun ins(id: Long, msg: Long) = Insight(id, 1, msg, "A", "A", "x?", 0, setOf(Category.REPLY), Priority.MEDIUM, "r", false)
        val out = Detector.pruneReplies(listOf(ins(1, 10), ins(2, 20)), mapOf(1L to 15L))
        assertEquals(listOf(20L), out.map { it.msgId }) // 10 was answered by my message 15; 20 came later and is still unanswered
    }
    @Test fun onlyLatestUnansweredKeepsReplyFlag() {
        fun ins(id: Long, msg: Long) = Insight(id, 1, msg, "A", "A", "x?", 0, setOf(Category.REPLY), Priority.MEDIUM, "r", false)
        val out = Detector.pruneReplies(listOf(ins(1, 10), ins(2, 20)), mapOf(1L to 5L))
        assertEquals(listOf(20L), out.map { it.msgId })
    }
}

class AiClassifierTest {
    private val src = mapOf(0 to "Meeting on 12 Nov at 5pm please confirm", 1 to "hello")
    @Test fun validJsonIsParsedAndValidated() {
        val raw = """```json
{"items":[{"i":0,"categories":["meeting","request","bogus"],"priority":"high","reason":"Asks to confirm a meeting","date_text":"12 Nov","uncertain":""}]}
```"""
        val r = AiClassifier.parse(raw, src).getOrThrow()
        assertEquals(setOf(Category.MEETING, Category.REQUEST), r[0]!!.categories); assertEquals(Priority.HIGH, r[0]!!.priority); assertEquals("12 Nov", r[0]!!.dateText)
    }
    @Test fun fabricatedDateTextIsRejected() {
        val r = AiClassifier.parse("""{"items":[{"i":0,"categories":["MEETING"],"priority":"LOW","reason":"x","date_text":"3 March"}]}""", src).getOrThrow()
        assertEquals("", r[0]!!.dateText)
    }
    @Test fun unknownIndexAndEmptyCategoriesAreDropped() {
        val r = AiClassifier.parse("""{"items":[{"i":9,"categories":["MEETING"]},{"i":1,"categories":[]}]}""", src).getOrThrow()
        assertTrue(r.isEmpty())
    }
    @Test fun garbageFailsGracefully() { assertTrue(AiClassifier.parse("sorry I can't", src).isFailure); assertTrue(AiClassifier.parse("{not json}", src).isFailure) }
}

class SseTest {
    @Test fun deltaIsExtracted() = assertEquals(SseEvent.Delta("Hi"), Sse.parseLine("""data: {"choices":[{"delta":{"content":"Hi"}}]}"""))
    @Test fun doneAndKeepAlive() { assertEquals(SseEvent.Done, Sse.parseLine("data: [DONE]")); assertEquals(SseEvent.Ignore, Sse.parseLine(": OPENROUTER PROCESSING")); assertEquals(SseEvent.Ignore, Sse.parseLine("")) }
    @Test fun nullContentAndMalformedAreIgnored() {
        assertEquals(SseEvent.Ignore, Sse.parseLine("""data: {"choices":[{"delta":{"content":null}}]}"""))
        assertEquals(SseEvent.Ignore, Sse.parseLine("data: {oops"))
    }
    @Test fun midStreamErrorIsReported() = assertTrue(Sse.parseLine("""data: {"error":{"message":"rate limited"}}""") is SseEvent.Failure)
}

class TaskLogicTest {
    private fun t(st: TaskStatus) = TaskV2(1, 1, 1, "c", "s", "title", "orig", 0, "", 0, st)
    @Test fun suggestedMustBeApprovedBeforeBeingActive() {
        assertEquals(TaskStatus.ACTIVE, TaskLogic.approve(t(TaskStatus.SUGGESTED)).status)
        assertEquals(TaskStatus.DISMISSED, TaskLogic.dismiss(t(TaskStatus.SUGGESTED)).status)
        assertEquals(TaskStatus.SUGGESTED, TaskLogic.setDone(t(TaskStatus.SUGGESTED), true).status) // cannot complete an unapproved suggestion
        assertEquals(TaskStatus.DONE, TaskLogic.setDone(t(TaskStatus.ACTIVE), true).status)
    }
    @Test fun reminderOptionsAreFutureOnly() {
        val now = 1_000_000_000_000L
        val o = TaskLogic.reminderOptions(now, now + 30 * 60_000L)
        assertTrue(o.all { it.second >= now + TaskLogic.MIN_LEAD_MS }); assertTrue(o.any { it.first == "At the due time" }); assertFalse(o.any { it.first == "1 day before" })
    }
    @Test fun userDueParsingIsStrict() { assertNotNull(TaskLogic.parseUserDue("2026-12-01 09:00")); assertNull(TaskLogic.parseUserDue("2026-13-45")); assertNull(TaskLogic.parseUserDue("tomorrow")) }
    @Test fun titleIsShortened() { assertTrue(TaskLogic.suggestionTitle("a".repeat(300)).length <= 90) }
}

class AutomationPolicyTest {
    @Test fun offNeverActs() = assertEquals(AutoAction.IGNORE, AutomationPolicy.decide(Mode.OFF, false, true, "Private"))
    @Test fun humanModeBlocksEverything() { for (m in Mode.values()) assertEquals(AutoAction.IGNORE, AutomationPolicy.decide(m, true, true, "Private")) }
    @Test fun channelsAndNonTextAreIgnored() { assertEquals(AutoAction.IGNORE, AutomationPolicy.decide(Mode.AUTO, false, true, "Channel")); assertEquals(AutoAction.IGNORE, AutomationPolicy.decide(Mode.AUTO, false, false, "Private")) }
    @Test fun approvalQueuesAndNeverAutoSends() = assertEquals(AutoAction.QUEUE_DRAFT, AutomationPolicy.decide(Mode.APPROVAL, false, true, "Group"))
    @Test fun onlyExplicitAutoSends() = assertEquals(AutoAction.AUTO_SEND, AutomationPolicy.decide(Mode.AUTO, false, true, "Private"))
}

class BriefingTest {
    @Test fun overviewCountsOnlyRealActiveData() {
        val now = 1_000_000_000_000L
        val a = Insight(1, 1, 1, "c", "s", "x", now, setOf(Category.IMPORTANT, Category.REPLY), Priority.HIGH, "r", false)
        val dismissed = a.copy(id = 2, msgId = 2, dismissed = true)
        val meeting = a.copy(id = 3, msgId = 3, categories = setOf(Category.MEETING), priority = Priority.MEDIUM, dueMs = now + 1000)
        val o = BriefingBuilder.overview(listOf(a, dismissed, meeting), emptyList(), 2, 7, now)
        assertEquals(1, o.important); assertEquals(1, o.pendingReplies); assertEquals(1, o.upcoming); assertEquals(7, o.unread); assertEquals(2, o.pendingTasks)
    }
}
