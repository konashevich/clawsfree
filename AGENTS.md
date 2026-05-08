# Agent Guide

## Project Overview

This is a native Android/Kotlin app for hands-free Telegram voice messaging while driving. The app registers assistant/media-button entry points, runs a foreground service for recording/playback, and talks to Telegram through TDLib.

Keep changes focused on the working MVP described in `docs/clawbot_telegram_handsfree_spec.md`. Do not add broader UX, diagnostics, setup flows, or unrelated production hardening unless the task explicitly asks for it.

## Repository Layout

- `app/src/main/java/io/openclaw/telegramhandsfree/` - app code.
- `app/src/main/java/io/openclaw/telegramhandsfree/config/` - `SharedPreferences` backed configuration.
- `app/src/main/java/io/openclaw/telegramhandsfree/audio/` - OGG/Opus recording and playback.
- `app/src/main/java/io/openclaw/telegramhandsfree/voice/` - foreground service, assistant services, and media-button receiver.
- `app/src/main/java/io/openclaw/telegramhandsfree/telegram/` - TDLib client facade, status model, outbox, and reflective bridge.
- `app/src/main/java/org/drinkless/tdlib/` - TDLib Java bindings. Treat these as generated/vendor files unless the task is explicitly about updating TDLib bindings.
- `app/src/main/res/` - XML layouts, drawables, strings, themes, and voice interaction metadata.
- `docs/` - product spec, bootstrap notes, and implementation plans.

## Build And Verification

Use the Gradle wrapper from the repo root. The project expects JDK 17 and Android SDK 35.

```powershell
.\gradlew.bat :app:assembleDebug
```

Useful checks when relevant:

```powershell
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

There are currently no dedicated unit or instrumentation test sources in the repo. For behavior touching assistant routing, media buttons, audio, foreground-service types, or TDLib auth/send/receive behavior, plan for physical-device verification in addition to a Gradle build.

## TDLib Artifacts

The app uses TDLib through a reflective bridge and also has generated `org.drinkless.tdlib` Java sources in-tree. Runtime native libraries are still required on device.

- Java/AAR artifacts can be placed in `app/libs/`.
- Native libraries live under `app/src/main/jniLibs/<abi>/`.
- Local binary/native artifacts and secrets are intentionally ignored by git. Do not delete or rewrite ignored local TDLib files unless asked.
- Do not commit `.env`, `local.properties`, `clawsfree-settings.local.json`, signing keys, or Telegram credentials.

## Coding Conventions

- Follow the existing Android View/XML style. This project does not use Compose.
- Keep Kotlin code simple and Android-platform native; avoid adding new frameworks for narrow changes.
- Preserve the distinction between Gradle `namespace` (`io.openclaw.telegramhandsfree`) and `applicationId` (`io.clawsfree.telegramhandsfree`).
- Use `ClawsfreeConfig` for persisted settings instead of introducing parallel preference stores.
- Keep service action strings and status/activity broadcasts centralized on `ClawsfreeForegroundService`.
- Gate Android-version-specific APIs with `Build.VERSION` checks, matching the existing pattern.
- Avoid blocking the main thread except for required Android UI/media APIs.
- In `TdLibReflectiveBridge`, maintain compatibility with both camelCase and snake_case TDLib field names when adding reflected fields.
- Leave TODO markers labeled `MVP-next` in place unless the task specifically resolves them.

## Behavior To Protect

When changing related code, preserve these flows:

- Setup saves Telegram auth settings separately from chat/topic binding.
- Foreground service starts promptly with a notification before doing long-running work.
- Long/assistant/media-button entry can start recording only after setup is complete and Telegram is connected.
- Any media button press while recording stops and sends the voice message.
- Recording uses OGG/Opus, a 20-second silence timeout, and a 10-minute hard cap.
- Incoming target-chat voice/audio replies play only when the app is awaiting a reply.
- Audio focus, Bluetooth SCO, wake locks, and media sessions are released on stop/destroy paths.

## Documentation

Update `README.md` or files under `docs/` when changing setup requirements, TDLib packaging, assistant-role behavior, permissions, or user-visible flows. Keep docs aligned with the MVP scope and avoid describing features that are not implemented.

## Working Tree Safety

Always check `git status --short` before and after edits. The repo may contain ignored local SDK/native artifacts needed for development; do not run cleanup commands that remove ignored files. Do not revert unrelated user changes.
