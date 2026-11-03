# Local AI Agent

A local-first Android AI assistant built with React Native 0.76.5, llama.rn, SQLite FTS5 and modular allow-listed skills.

## What is included

- On-device GGUF model download and inference (Qwen2.5 0.5B for low-memory devices; Qwen2.5 1.5B for higher-memory devices).
- SQLite FTS5 retrieval for imported text and digital PDF documents.
- Native Android PDF text extraction using Apache PDFBox-Android. It reads small batches of pages; image-only/scanned PDFs need OCR and are not text-extracted.
- DuckDuckGo Instant Answer and Wikipedia lookups with a local cache.
- Explicit confirmation before opening call, WhatsApp or alarm intents.
- Dynamic skill registry that only enables built-in allow-listed handlers. Imported JSON is metadata-only; downloaded JavaScript is never evaluated.
- User-visible WebView inspection for pages the user opens.
- Best-effort daily briefing scheduler using an Android foreground service.

## Build APK on GitHub

Push to `main` or manually run **Build Android APK** from Actions. The workflow installs Node 20 and JDK 17, generates the React Native 0.76.5 Android native scaffold in the runner, patches the PDFBox bridge and service manifest, then builds `assembleDebug`. The generated native scaffold is ephemeral and is not committed.

Download the `local-ai-agent-debug-apk` artifact from the successful workflow run.

## Run locally

Install Node 20 and JDK 17, then run:

```sh
npm install
npx @react-native-community/cli@15.0.1 init LocalAIAgent --version 0.76.5 --package-name com.localaiagent --skip-install --skip-git-init --pm npm
cp -R LocalAIAgent/android ./android
python3 scripts/prepare_android.py android
npx react-native start
```

In a second terminal, run `cd android && ./gradlew assembleDebug`.

## Model storage

Models are downloaded only after the user presses the download button. They are stored in the app's private documents directory and are not included in Git. Qwen model cards identify Apache-2.0 licensing; keep the model license and attribution with any redistribution.

## Important platform limits

- The app cannot guarantee 24/7 execution. Android may stop background work; Android 12+ restricts starting foreground services from the background, and newer Android releases impose service limits. Start the scheduler from the open app and keep its persistent notification visible.
- WebView inspection is user-visible; it is not a hidden browser and does not automate logins, bypass CAPTCHAs, or bypass website terms.
- Calls and WhatsApp messages open the relevant dialer/app with user confirmation. The app does not silently place calls or send messages.
- Alarm intents depend on an installed clock app that accepts Android's SET_ALARM action.
- Small local models are not equivalent to a large cloud model. Search and document retrieval improve factual grounding but cannot guarantee zero errors.
- OCR for scanned PDFs, contact-name resolution, auto-posting to social platforms, and guaranteed reboot-persistent cron execution are not included.

## License notes

The application code is intended to be original. React Native, llama.rn, and the bundled native dependencies retain their respective licenses. Qwen2.5 model cards show Apache-2.0; verify the exact model card and license before redistributing model weights.
