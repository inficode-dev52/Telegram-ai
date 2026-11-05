# ADITYA AI — source, NOT compiled or run
No Android SDK/network was available when this was written: nothing has been compiled, installed or tested. Expect compile errors to fix on first build (TDLib API signatures vary by version; code targets 1.8.2x+).
## Setup
1. Build TDLib for Android (tdlib/td example/android) -> `app/libs/tdlib.aar` exposing org.drinkless.tdlib.Client/TdApi.
2. `local.properties` (gitignored) from the example, or GitHub secrets TG_API_ID / TG_API_HASH. CI fails with a clear message if the AAR or secrets are missing.
3. Push; Actions builds the APK (`gradle :app:assembleRelease`, debug-signed).
## Implemented in source (untested)
Splash/login animations, shimmer, animated counts; Home (live unread/chats/AI replies/open items, connection, attention list); Chats (filters incl. archive/bots/saved, avatars, pinned/muted, unread); chat screen (history, send text/photo/document, reply, forward, edit, delete, status icons, photo display, AI draft, summary); Brain (daily briefing, tasks, inbox of important/questions, AI drafts approval, memory with controls); Activity timeline; notifications; search (chats + cached message history); app lock, secure-screen, themes, OpenRouter settings + test; foreground service showing real connection state; network-change handling; AI modes + Human Mode + "— Aditya" signature unchanged.
## Known limitations / not done
- Message search covers locally cached history only (warmed from latest 60 msgs of top 30 chats), not Telegram server-wide search.
- Important/task/question detection is keyword heuristics, not AI.
- No video/voice/sticker playback, no document download/open, no pin/mute toggles, no scheduled auto-briefing.
- Foreground service cannot be restarted after boot/swipe-kill on modern Android; background AI only runs while the process is alive. OEM battery managers may kill it.
- App lock re-locks whenever the app leaves the foreground (except when launching the attachment picker).
- API hash is embedded in the APK (unavoidable for client TDLib).
