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
