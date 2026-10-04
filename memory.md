# MyAIAgent — Project Memory

## Rules
- Repository: bramhanand564-bit/MyAIAgent
- Branch: main ONLY.
- Mobile + GitHub development.
- No Android Studio or PC required.
- YouTube Data API is NOT used for upload automation.

## Goal
Phone files/folders → MyAIAgent queue → metadata → scheduler → YouTube automation → result/status → next item.

## YouTube Modes
1. Embedded YouTube workspace.
2. External installed YouTube app fallback through Android UI automation.

## Implemented
- Android project/build configuration.
- GitHub Actions debug APK workflow.
- MainActivity shell.
- AccessibilityService foundation.
- Multi-video picker.
- Persistent local upload queue using SharedPreferences/JSON.

## Queue Item
Supports the data model for:
- video URI
- filename
- title
- description
- thumbnail
- visibility
- scheduled time
- status

## Reliability
- User-authorized files/accounts only.
- No CAPTCHA/security/quota/rate-limit bypass.
- UI state detection, timeouts, retries and duplicate-upload protection.
- Android background/battery restrictions must be handled.
- YouTube UI changes may require maintenance.

## Next
1. Queue item editor.
2. Folder picker.
3. Scheduler/foreground execution.
4. Embedded YouTube workspace.
5. External YouTube automation state machine.
6. Upload verification/retry.
7. AI video-generator workflow within each service's allowed limits.
