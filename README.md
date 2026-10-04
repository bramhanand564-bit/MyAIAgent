# MyAIAgent

Android automation workspace for a user-authorized YouTube upload queue.

## Core flow

Phone video/file or folder -> persistent queue -> title/description/visibility -> schedule -> YouTube UI automation -> result/status -> next queued item.

## YouTube modes

- EMBEDDED_WEB: opens YouTube Studio inside MyAIAgent and supplies the queued video to the web uploader file chooser.
- EXTERNAL_APP: hands the queued video URI to the installed YouTube app with an Android share intent, then the AccessibilityService continues the supported UI flow.

No YouTube Data API is used for upload automation.

## Device test

1. Install the MyAIAgent-debug APK from the latest successful GitHub Actions build.
2. Open MyAIAgent.
3. Enable the MyAIAgent Accessibility Service in Android Accessibility settings.
4. Grant the app access to a test video using Add Videos or a folder using Add Folder.
5. Open the queue item and set title, description, visibility and YouTube mode.
6. Use Run Now for a manual end-to-end test.
7. For scheduling, set a future time and enable exact alarms when the device offers that option.
8. Watch the queue status and result note after the YouTube flow.
9. Test Retry Upload, Remove from Queue, and duplicate import behavior.

## Safety / reliability

Automation pauses on sign-in, verification, CAPTCHA, or security-check screens. It does not bypass those controls.

The automation uses accessibility labels/content descriptions and bounded retries rather than fixed screen coordinates.

Android background-activity restrictions may require the user to bring the relevant UI to the foreground on some device/OS configurations.

YouTube can change UI labels or flows; selectors may need maintenance after such changes.

## CI

The GitHub Actions workflow builds a debug APK on pushes to main and keeps only the latest main-branch build active through workflow concurrency.