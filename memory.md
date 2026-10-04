# MyAIAgent — Project Memory

## 1. Project Rule
- Repository: bramhanand564-bit/MyAIAgent
- Development branch: main ONLY
- No extra branches.
- No Android Studio.
- No PC/laptop required for development.
- Development workflow is mobile + GitHub.
- This file is the permanent project memory and should be updated as major decisions are made.

## 2. Project Goal
Build a mobile Android app named MyAIAgent that can automate scheduled YouTube video uploads using UI/device automation.

The app should allow the user to:
1. Select individual videos from the phone.
2. Grant access to a folder/directory.
3. Add additional videos or folders later.
4. Configure upload details manually.
5. Create an upload queue.
6. Assign an exact date/time to each upload.
7. At the scheduled time, automate the YouTube upload workflow through the visible YouTube UI.
8. Fill the required upload information automatically.
9. Upload/publish according to the user's configured schedule.

## 3. Important API Decision
**YouTube Data API is NOT to be used for the upload workflow.**

The intended approach is Android UI/device automation using the user's authenticated YouTube/Google session.

## 4. File/Folder Access
The app should use Android's user-facing file/folder selection mechanisms.
- Single video selection.
- Folder selection.
- Persistent permission where Android allows it.
- Multiple sources can be added.
- Only user-authorized files/folders should be processed.
- Maintain a local queue/database of selected media and metadata.

## 5. Upload Item
Each queued video should eventually support:
- Source video/file
- Filename
- Title
- Description
- Thumbnail
- Tags/metadata where applicable
- Visibility
- Schedule date
- Schedule time
- Upload state
- Progress/status
- Error/retry state

## 6. Queue Concept
Example queue:
- Video 01 — 20:00 — Scheduled
- Video 02 — 21:00 — Scheduled
- Video 03 — 22:00 — Scheduled
- Video 04 — Next day 20:00 — Scheduled

The scheduler should process items in their configured order/time.

## 7. Automation Architecture
Phone → MyAIAgent → File/Folder Permission → Local Media Queue → Metadata Configuration → Scheduler → Android Automation Layer → YouTube UI → Video Selection → Metadata Entry → Thumbnail/Visibility/Schedule → Upload/Publish → Result/Status → Next Queue Item

## 8. Android Automation
The automation layer may use Android Accessibility/automation capabilities where appropriate.
- Automation should work with the actual visible UI.
- Prefer detecting screens/states instead of blindly tapping fixed coordinates.
- Include timeout handling.
- Detect failures.
- Support retry/recovery.
- Avoid duplicate uploads when an upload has already completed.
- YouTube UI changes may require maintenance.

## 9. Background/Scheduling Reality
Android background restrictions can affect long-running automation.
Design must account for battery optimization, background restrictions, device sleep, accessibility service availability, YouTube app/browser state, network availability, login/session state, and file permission persistence.

## 10. Development Rules
- Work only in main.
- Do not create unnecessary files.
- Do not add unrelated features.
- Do not modify other repositories.
- Build incrementally.
- Test each major component before moving forward.
- Discussion comes before major architectural changes.
- Do not silently substitute YouTube API for UI automation.

## 11. Current Status
- Repository was reset to an empty state before this project.
- memory.md is the first project file.
- No application implementation has been created yet.
- Next work should establish the minimum Android project structure needed for the app.

## 12. Initial Development Direction
1. Android project/build configuration compatible with GitHub Actions.
2. Basic MyAIAgent application shell.
3. File/video picker.
4. Folder permission flow.
5. Local media queue.
6. Basic upload-item editor.
7. Scheduler foundation.
8. Automation service foundation.
9. YouTube UI automation workflow.
10. Error handling, retry and status reporting.

No YouTube API upload integration.