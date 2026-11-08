package com.aditya.ai.ai

enum class AutoAction { IGNORE, AUTO_SEND, QUEUE_DRAFT }

/**
 * The OFF / AUTO / APPROVAL decision, extracted unchanged from ReplyEngine so it can be tested.
 * Human mode, channels, non-text messages and OFF always ignore. Only an explicit AUTO chat mode sends without approval.
 */
object AutomationPolicy {
    fun decide(mode: Mode, humanMode: Boolean, isText: Boolean, chatKind: String): AutoAction = when {
        humanMode || !isText || chatKind == "Channel" -> AutoAction.IGNORE
        mode == Mode.OFF -> AutoAction.IGNORE
        mode == Mode.AUTO -> AutoAction.AUTO_SEND
        else -> AutoAction.QUEUE_DRAFT
    }
}
