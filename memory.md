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

## Implemented
- Android project/build configuration.
- GitHub Actions debug APK workflow.
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
- External YouTube app launch fallback.
- Per-item automation mode selection.

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
- Prefer UI state detection over fixed coordinates.
- Use timeouts, retries, recovery and duplicate-upload protection.
- Android background/battery restrictions must be handled.
- Background activity-launch restrictions can affect scheduled UI automation.
- YouTube UI changes may require maintenance.

## Scheduler
- Scheduled uploads use Android AlarmManager.
- Android 12+ exact-alarm access is handled through system settings.
- A foreground runner starts at the scheduled time and hands off to the selected YouTube mode.
- Scheduling after reboot/package replacement is restored from the local queue.

## Planned Next
1. Real YouTube upload state machine in AccessibilityService.
2. UI-state detection for YouTube/Studio screens.
3. Video selection and metadata entry automation.
4. Upload completion verification and retry.
5. Better queue controls: remove, reorder, duplicate protection.
6. AI video-generator workflow within each service's allowed limits.
