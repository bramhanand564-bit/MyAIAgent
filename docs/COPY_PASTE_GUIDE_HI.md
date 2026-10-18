# Copy/Paste and GitHub Guide — NaxBot AI Agent

## Current working branch

**feature/naxbot-agent-foundation**

The existing main branch is being preserved while the new version is developed. Changes should be reviewed on this feature branch before they are merged into main.

## How to use the APK build

1. Open https://github.com/bramhanand564-bit/MyAIAgent
2. Tap **Actions**.
3. Open **Build NaxBot AI Agent APK**.
4. Download the **NaxBot-AI-Agent-Debug-APK** artifact when the run is green.
5. Unzip it and install app-debug.apk on the phone.

If a run fails, open the failed job and read the first Gradle/Kotlin error. Do not delete source files just to make the build green.

## How code patches will be delivered

For each implementation patch, follow this sequence:
1. Exact path to open or create.
2. Whether the file is new or a complete replacement.
3. Full copy/paste content for files that must be manually edited.
4. The GitHub commit / branch reference for the same code.
5. Build result and the exact manual phone test.
6. Known limitations and what should be checked before enabling scheduled public uploads.

When a change spans Kotlin + XML, both sides will be listed. Do not replace an XML view ID without updating the Kotlin findViewById reference that uses it.

## API-key safety

- Never paste the Gemini API key into a source file, README, issue, chat log or GitHub Actions log.
- Enter it directly on the phone in the app's Settings screen.
- The prototype stores a key on-device for personal testing. For an APK shared with other people, a backend proxy is safer because APK client secrets can be extracted.
- Check the model's availability and pricing in Google AI Studio before enabling repeated scheduled tasks.

## UI automation expectations

The YouTube route uses the official app/site UI and Android Accessibility Service; it does not upload through the YouTube Data API. It depends on the screen being usable. Do not expect it to tap while the phone is powered off. If a CAPTCHA, 2FA or unexpected screen appears, the task must pause for the user rather than trying to bypass the challenge.
