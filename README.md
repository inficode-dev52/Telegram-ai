# ADITYA AI — Personal Intelligence System (Android, Kotlin + Compose + TDLib)

## Status
This upgrade was written **without access to an Android SDK, Gradle or network**, so it has **not been compiled, unit-tested or run by the author**.
Treat the first CI run as the first real compile; fix any compiler errors it reports before using the APK.
CI (`.github/workflows/build.yml`) now runs `:app:testDebugUnitTest` before `:app:assembleDebug` and runs on `feature/**` branches (releases are still published only from main/master).

## What is new
- **Brand**: original adaptive launcher icon (orb + three orbital rings), monochrome icon, notification icon, in-app mark. Midnight/cyan design tokens (`ui/Design.kt`).
- **Startup**: cinematic Canvas intro (`ui/Intro.kt`), full on first launch, short afterwards, skippable, reduced-motion aware, configurable in Settings.
- **Command Center** (`ui/Command.kt`): greeting/date/connection/alerts, six-state AI orb, metrics computed from real chat/stored data (shows "Off" instead of fake zeros), quick actions.
- **Message intelligence** (`intel/`): opt-in, per-chat allow list, rule-based detection with optional validated AI JSON classification. Each item shows confirmed data, interpretation (rule/AI) and uncertainty separately.
- **Briefing, tasks (suggest → approve), replies/drafts (persisted), memory (user-approved only), reminders (explicit confirmation, inexact alarms)**.
- **Assistant** (`ai/AssistantEngine.kt`, `ui/Assistant.kt`): streaming OpenRouter replies, retry, copy, clear-with-confirmation, grounded context, local fallback answers without an API key, tap-to-talk and TTS (`voice/VoiceIO.kt`).

## Preserved on purpose (unchanged byte-for-byte)
`td/Telegram.kt` (auth, TDLib init, `files/td` + `files/tdfiles` directories), `org/drinkless/tdlib/*`, `applicationId com.aditya.ai`, API ID/HASH handling, the existing `aditya_secure` store and all its keys.
New data lives in a separate encrypted file `aditya_v2_secure`.

## Behaviour changes to be aware of
- Tasks are no longer created silently from private messages; they appear as *suggestions* when message intelligence is on.
- The approval-queue drafts now survive restarts. OFF/AUTO/APPROVAL semantics are unchanged (`ai/AutomationPolicy.kt`, tested).
- `versionCode` 1 → 2 (`versionName` 0.2).

## Setup
1. TDLib Java sources and `libtdjni.so` (arm64-v8a) are already in the repo.
2. GitHub secrets `TG_API_ID`, `TG_API_HASH` (or `local.properties` locally). OpenRouter key is entered in Settings.
3. Push; Actions builds the APK.

## Known limitations
- "Open conversation" opens the chat, not the exact message.
- Message intelligence scans cached history of up to 25 recent chats (last 3 days), not the whole Telegram server.
- Dates are only taken from message text; relative dates are resolved from the message time and flagged uncertain.
- Voices depend on the device's TTS engine; no voice gender is assumed.
- No always-on listening.
- **Signing:** the workflow builds with a freshly generated Android debug key unless one already exists on the runner; the generated `debug.keystore` in the workflow is not referenced by Gradle. An APK signed with a different key cannot update an installed one, and Android then requires uninstalling (which erases the Telegram session). See the final report before installing over an existing install.


## November 2026 polish update
- Updated the default light palette to white surfaces with violet and blue accents; dark mode remains available.
- Refined card surfaces and borders for a cleaner Material 3 appearance.
- Reminder scheduling now uses exact-and-while-idle alarms when Android grants `SCHEDULE_EXACT_ALARM`; otherwise it safely falls back to inexact alarms and informs the user.
- Added a Settings shortcut to Android's exact-alarm access screen.

### Important verification notes
This source archive has not been compiled or installed in this environment. Exact reminder delivery depends on the user granting Android's special exact-alarm access and notification permission. Telegram background delivery, OpenRouter connectivity, and device-specific battery restrictions still require on-device verification. Do not treat this ZIP as a verified APK.
