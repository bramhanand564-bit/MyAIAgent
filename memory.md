# MyAIAgent — Project Memory

## Rules
- Repository: bramhanand564-bit/MyAIAgent
- Branch: main ONLY.
- Mobile + GitHub development.
- No Android Studio or PC required.
- YouTube Data API is NOT used for upload automation.

## Goal
Phone files/folders → MyAIAgent queue → metadata → scheduler → YouTube UI automation → result/status → next item.

## YouTube Modes
- EMBEDDED_WEB: in-app YouTube Studio WebView workspace.
- EXTERNAL_APP: installed YouTube app fallback, controlled through Android UI automation.
- Queue items can choose the mode.
- External mode hands the selected, user-authorized video URI directly to the YouTube app with an Android share intent.

## Implemented
- Android project/build configuration.
- GitHub Actions debug APK workflow with latest-main concurrency.
- MainActivity shell.
- AccessibilityService foundation.
- Multi-video picker.
- Folder picker with recursive video import.
- Persistent local upload queue using SharedPreferences/JSON.
- Queue item editor for title, description, visibility and schedule.
- Exact Alarm scheduling foundation.
- Scheduled foreground runner.
- Reboot/package-replaced schedule restoration.
- Embedded YouTube Studio workspace.
- External YouTube handoff via ACTION_SEND.
- Per-item automation mode selection.
- Accessibility upload state machine with bounded retries, session timeout and stale-session watchdog.
- Security-screen detection that pauses automation and requires the user to act.
- Direct external YouTube composer handoff.

## Automation State Machine
WAITING_FOR_APP → FIND_CREATE → FIND_UPLOAD → WAITING_FOR_PICKER → FILL_DETAILS → SET_VISIBILITY → PUBLISH → VERIFY
Optional pause state: WAITING_USER

The service uses accessibility node text/content descriptions and clickable ancestors instead of fixed screen coordinates. Unsupported/security-gated screens are not bypassed.

## Queue Item
Data model includes:
- video URI
- filename
- title
- description
- thumbnail URI field
- visibility
- scheduled time
- status
- automation mode

## Reliability
- User-authorized files/accounts only.
- No CAPTCHA/security/quota/rate-limit bypass.
- UI matching is label-based and may need maintenance when YouTube changes.
- Each state has bounded retries; sessions have a timeout and watchdog.
- Login, verification, CAPTCHA or security-check screens are paused as NEEDS_USER_ACTION.
- Android background/battery restrictions must be handled.
- Background activity-launch restrictions can affect scheduled UI automation.
- Upload completion currently means an explicit published/upload-complete/processing UI signal was detected; final public availability verification is still pending.

## Scheduler
- Scheduled uploads use Android AlarmManager.
- Android 12+ exact-alarm access is handled through system settings.
- A foreground runner starts at the scheduled time and hands off to the selected YouTube mode.
- Scheduling after reboot/package replacement is restored from the local queue.

## Planned Next
1. Device-tested YouTube selectors/state transitions and upload flow refinement.
2. Upload completion verification and persistent result details.
3. Queue ordering and batch scheduling.
4. Embedded workspace upload/file chooser integration.
5. AI video-generator workflow within each service's allowed limits.
6. Final device validation with a real user-authorized test video.


## Latest Reliability Upgrades
- External YouTube mode uses a direct video share handoff to the installed YouTube package, avoiding the system file picker for that mode.
- Concurrent scheduled jobs are serialized by the automation session store; collisions are deferred instead of overwriting an active session.
- Queue items persist last-run time and a human-readable result note.
- Embedded WebView file chooser is connected to Android's document picker.
- Exact alarm scheduling falls back to an inexact while-idle alarm when exact-alarm access is unavailable, and exact-permission changes trigger rescheduling. This follows Android's documented alarm behavior. 


## CURRENT MASTER STATE — October 2026

### Product Direction
- MyAIAgent is a mobile-first Android automation app for user-authorized video workflows.
- Primary workflow: select/import videos → build queue → schedule → open official native YouTube Studio → automate UI with AccessibilityService → verify every step → monitor upload → record result.
- The app must perform real actions. UI status must not claim success unless the underlying action/verification actually happened.
- Separate screens/pages are preferred over putting every function on Home.
- UI direction: polished dark/glass, professional, clean, Apple-inspired; avoid prototype-like screens.
- Development is done through GitHub/mobile; only the main branch is used.

### Build Foundation
- AGP 8.7.3
- Kotlin Android plugin 2.0.21
- compileSdk 35
- minSdk 26
- targetSdk 35
- applicationId: com.myaiagent
- JVM 17
- Core dependencies: activity-ktx 1.10.0, core-ktx 1.15.0, appcompat 1.7.0, documentfile 1.0.1, material 1.12.0.
- GitHub Actions builds :app:assembleDebug and publishes MyAIAgent-debug.
- Never create extra branches.

### Queue / Data
UploadItem now carries:
- id
- uri
- fileName
- title
- description
- thumbnailUri
- visibility
- scheduledAt
- status
- automationMode
- contentType (VIDEO/SHORT)
- lastRunAt
- resultNote
Default automation mode: NATIVE_STUDIO.
Queue is persisted in SharedPreferences as JSON.
Queue supports duplicate-control, update, remove and retry reset.

### Workflow
WorkflowConfig persists:
- enabled
- folderUri
- times
- dailyLimit
- visibility
- automationMode
- contentType
WorkflowSetup supports folder import, 1/2/3 uploads per day, up to three configured times, visibility, VIDEO/SHORT, test-one-video, Start/Stop workflow.
WorkflowReconciler imports newly discovered folder videos, applies current workflow settings and schedules future queue items.
Reconciliation is invoked from app lifecycle/scheduler/boot paths so a workflow can be configured once and continue across future files.
Stop workflow cancels scheduled items and returns them to QUEUED.

### Scheduling
UploadAlarmScheduler:
- exact AlarmManager when permitted;
- inexact fallback when exact-alarm permission is unavailable;
- schedule/scheduleAt/scheduleSoon/cancel/rescheduleAll;
- boot/package-replaced/exact-permission restoration.
Only pending QUEUED/SCHEDULED items with a future scheduledAt are rescheduled.
Scheduled execution uses PendingIntent/foreground runner paths hardened for Android 14+ background activity-launch restrictions.

### Native YouTube Studio Architecture
- Official native YouTube Studio package: com.google.android.apps.youtube.creator.
- Main YouTube fallback: com.google.android.youtube.
- MyAIAgent launches Studio as its own Android app surface; it does not embed another app's Activity.
- AccessibilityService controls the native Studio UI after launch.
- YouTube Data API is not used.
- Google login/security/CAPTCHA screens are never bypassed; automation pauses and waits for manual user action.

### Native Automation Flow
Main state sequence:
WAITING_FOR_APP
→ FIND_CREATE
→ VERIFY_CREATE_MENU
→ FIND_UPLOAD
→ VERIFY_UPLOAD_PICKER
→ WAITING_FOR_PICKER
→ FILL_DETAILS
→ SET_VISIBILITY
→ PUBLISH
→ MONITOR_UPLOAD
→ VERIFY
→ COMPLETE
Optional: WAITING_USER / ERROR.

The service:
- accepts YouTube/YouTube Studio and supported Android picker packages;
- detects security/login/CAPTCHA screens only in automation packages;
- verifies Create menu before proceeding;
- verifies upload/picker UI before proceeding;
- selects the exact target filename rather than any visible file;
- fills title/description and reads values back;
- sets visibility;
- publishes;
- monitors real transfer/progress/failure signals;
- requires actual upload/published evidence before SUCCESS;
- treats Processing alone as NOT success;
- uses a watchdog and bounded retries.

### Real Gesture Reliability
Accessibility is the exact-control layer.
- clickByLabels first uses ACTION_CLICK on enabled/clickable nodes or nearest clickable ancestor.
- If ACTION_CLICK fails, live screen bounds are used and a real gesture tap is dispatched.
- setText first uses ACTION_SET_TEXT; fallback taps the live field, copies text to clipboard and tries ACTION_PASTE.
- Gesture callbacks record dispatched/completed/cancelled/rejected outcomes.
- Picker file selection similarly prefers the nearest enabled clickable ancestor and falls back to a real gesture tap.
- Picker Open/Done is blocked until target-file selection is confirmed.
- Picker selection pending state is stored in AutomationSessionStore.
- Picker can scroll when the target filename is not currently visible.

### Google Photos Picker Fix
Real device/video testing showed the file chooser was Google Photos rather than the standard DocumentsUI picker.
Observed test video: 1000278592.mp4.
Root cause: com.google.android.apps.photos was not recognized as a supported automation/picker package, so picker state handling never activated and auto-tap stopped.
Fix committed on main:
- commit: 2a4495ed1ace0f3aaaec8a02f2ab7b4bd8b93834
- message: Support Google Photos picker in automation
- Google Photos package com.google.android.apps.photos is now recognized by isDocumentPicker(), which makes it an accepted automation package.
Next validation must confirm the picker actually advances, not merely that the package is recognized.

### Exact-File AI Targeting
GeminiVisionAgent and OpenAiCompatibleVisionAgent now receive targetFileName.
Vision prompts explicitly state the exact target filename and instruct the model to prioritize that file in PICKER state instead of selecting another visible file.
NaxAccessibilityService passes the current UploadItem.fileName to vision analysis.
This is a visual fallback, not a security bypass.

### Vision / Gemini
Gemini is used as the visual brain, not as a fake success generator.
Architecture:
screenshot → Gemini → target/action → Accessibility gesture → screenshot → verification.
Default model: gemini-3.1-flash-lite.
GeminiVisionAgent receives WorkflowMemory context plus target filename.
GeminiRequestGate enforces a 15-second app-side minimum request interval.
AiChatClient uses the same request gate.
No hard-coded API keys.
Vision actions are restricted to SET_TEXT, SELECT_FILE, CLICK and NEEDS_USER.
NEEDS_USER is used for security/login/CAPTCHA/manual-action situations.

### Workflow Memory
WorkflowMemoryStore remembers successful UI targets:
- package
- label/text/contentDescription
- bounds
- enabled/clickable information
- normalized x/y coordinates based on screen size.
Up to 20 remembered entries are supplied as context to vision/findNode.
Remembered targets are hints, never proof; current UI verification remains mandatory.
Successful gesture targets, including picker-file actions, can be remembered.

### NAX Mind / AI
NAX Mind tracks:
- active automation
- item/file
- state and state age
- retry count
- package
- observations
- diagnosis/fix/severity
- recovery request
- event history.
MindEngine provides per-state diagnosis, stuck warnings, bounded retry escalation and safe recovery.
NAX Chat receives live flow context and recent events.
Safe recovery can reopen Studio for normal app states; security states remain manual.
No bypass logic is allowed.

### Automation Session / Live State
AutomationSessionStore:
- starts/ends sessions
- mirrors current state
- handles test mode
- retry tracking
- waiting-user resume state
- picker selection pending
- transfer-complete flag
- last observation
- 10-minute active-session timeout.
AutomationLiveStore:
- active/item/file/state/result/note
- human-readable events
- stale-session detection.
TestRunStore:
- dedicated one-video test session
- mirrors state/events
- only marks SUCCESS after real verification
- removes test item from production queue when test finishes.

### UI / Navigation
Main dashboard includes status, last test, live automation, AI settings, workflow, test one video, pages, KPI, Create/System/Queue.
Separate pages exist for Queue, History and App Settings.
Shared NaxBottomNav has:
Home | Queue | Workflow | Settings
Workflow is the central/wider tab.
Workflow, Queue, App Settings, Workflow Test and AI API settings use the shared bottom navigation.

### Automation Test Evidence
Earlier test 1000278587.mp4 showed the flow reaching Google Photos/file-picker screens and later YouTube Studio/Add Details, but user reported no automatic tapping.
Test 1000278592.mp4 was inspected frame-by-frame:
- Google Photos Videos picker remained visible for a long period.
- YouTube Studio was visible behind the picker.
- Automation did not progress because Google Photos was not recognized as a picker package.
That exact root cause is fixed in commit 2a4495ed1ace0f3aaaec8a02f2ab7b4bd8b93834.
Latest user test file: 1000278596.mp4. It is the next validation artifact; do not claim its behavior has been analyzed unless it is actually inspected.

### Required Next Validation
After installing the latest successful main build:
1. Enable Accessibility.
2. Configure Gemini API key if visual fallback is desired.
3. Workflow → Test one video.
4. Use VIDEO for normal video testing or SHORT for Shorts testing.
5. Watch for:
   FIND_CREATE → VERIFY_CREATE_MENU → FIND_UPLOAD → VERIFY_UPLOAD_PICKER → WAITING_FOR_PICKER → FILL_DETAILS.
6. In picker, verify exact filename selection before Open/Done.
7. Verify real gesture/action logs.
8. Verify title/description read-back.
9. Verify visibility selected state.
10. Verify Publish/Upload action.
11. Verify MONITOR_UPLOAD evidence.
12. Only then allow VERIFY → SUCCESS.

### Non-Negotiable Reliability Rules
- Never blindly tap coordinates when a verified accessibility target is available.
- Never choose a different file just because it is visible.
- Never mark Processing as successful upload.
- Never claim an automation is active when no real session is active.
- Never bypass CAPTCHA, Google security, login verification, rate limits or quota limits.
- Never use YouTube Data API for this upload workflow.
- Keep all changes on main.
- Prefer fixing the actual observed root cause over adding random retries.
