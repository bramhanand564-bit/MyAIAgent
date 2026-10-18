# NaxBot AI Agent (MyAIAgent)

Native Android AI phone-automation prototype built with Kotlin. The goal is to let the user describe a task in Hindi/Hinglish, use Gemini for planning, and execute user-approved screen actions through Android Accessibility Service with verification and a clear report.

## Main direction

- Natural-language task entry and Gemini model selection.
- Accessibility-based screen observation and taps.
- Persistent task queue, retries, Pause / Resume / Cancel / Stop.
- User-selected video folder and upload history.
- YouTube upload through the official app/site UI (not YouTube Data API upload).
- AI-generated video title, description and tags.
- A success report only when the UI gives usable completion evidence.
- GitHub Actions builds a debug APK artifact.

## Current implementation status

- The public auto-publish switch is OFF by default and requires an explicit confirmation.
- Review Mode blocks the final Publish/Post action. Auto-Publish also requires Accessibility UI evidence that Public is selected; when this cannot be verified, the agent pauses for review.
- Gemini metadata generation fails closed instead of substituting a generic made-up title when the model response is invalid.
- The daily YouTube feature is currently notification-triggered; the user must tap the reminder to start the UI workflow. Fully unattended daily posting and real-device reliability testing remain future milestones.

## Important limitations

UI automation requires the phone to be on and the relevant app UI to be accessible. A phone powered off cannot perform screen taps. CAPTCHA, 2FA, unexpected permission dialogs or unclear upload results should pause the agent for the user. APK build success does not replace testing on a real Android phone.

## Development branch

The new foundation is being developed on **feature/naxbot-agent-foundation**. The existing main branch is kept separate during development.

## Guides

- [NaxBot architecture and acceptance criteria](docs/NAXBOT_AGENT_BLUEPRINT_HI.md)
- [Hindi GitHub APK and copy/paste guide](docs/COPY_PASTE_GUIDE_HI.md)

## Build APK from GitHub

1. Open **Actions**.
2. Select **Build NaxBot AI Agent APK**.
3. Open a successful run and download **NaxBot-AI-Agent-Debug-APK** from the Artifacts section.
4. Extract the ZIP and install app-debug.apk.

## API-key safety

Never commit a Gemini API key to the repository. For personal prototype use, enter it through the app's Settings screen. An APK client cannot perfectly conceal a key from a determined reverse engineer; a backend proxy is recommended before wider distribution.
