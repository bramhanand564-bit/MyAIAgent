# NaxBot AI Agent — Project Blueprint (Hindi)

## Mission

MyAIAgent ko ek native Android **AI phone-automation assistant** mein evolve karna hai. User Hindi/Hinglish mein goal bolega; Gemini plan banayega; Android Accessibility Service screen/UI observe karke actions karegi; har important action ke baad verify hoga; task status aur report local device par save honge.

YouTube example first supported workflow hoga. Architecture generic rahegi taaki baad mein browser form filling, file organization, reminders aur doosre user-authorized workflows add kiye ja saken.

## Non-negotiable requirements

1. Android APK GitHub Actions se build hoga; user ko PC ki zaroorat nahi.
2. YouTube Data API se upload nahi karna. Official YouTube app / YouTube Studio / browser UI ko Accessibility Service se operate karne wala UI automation route use hoga.
3. Video folder user Android file picker se choose karega. App ko sirf user-granted folder access milega.
4. Har video ka persistent status hoga; app retry karte waqt known-published video ko dobara upload karne se bachega.
5. AI title, description aur tags suggest karega. UI ko fill karne ke baad fields verify honge.
6. Published status sirf tab report hoga jab screen se publish-success evidence mil jaye. Evidence na mile to NEEDS_CONFIRMATION report hoga; success kabhi guess nahi ki jayegi.
7. Public auto-publish ek explicit user-controlled setting hogi. Default review mode safe rahega; user setting enable karke scheduled public publishing authorize kar sakega.
8. Global Stop control hamesha available rahega. Pause, resume, cancel aur retry alag actions honge.
9. API key source code, Git commits, screenshots ya logs mein nahi jayegi. Prototype mein key device settings mein enter hogi; public distribution ke liye backend proxy zyada secure hai, kyunki APK ke andar client key ko completely hide nahi kiya ja sakta.
10. CAPTCHA, 2FA, login challenge ya unexpected permission prompt ko bypass nahi karna. Aisi condition par agent pause karke user ko hand-off karega.

## Gemini model strategy (2026-10-18 par checked)

- Default cost-conscious model: **gemini-3.1-flash-lite**.
- Optional higher-capability model: **gemini-3.8-flash**; isko use karne se pehle Google AI Studio / API project mein access aur billing verify karna.
- Model IDs settings se changeable rahenge. Preview ya unavailable model ke fail hone par app bounded fallback karegi, infinite retries nahi.
- Model ka naam availability, quota ya free access ki guarantee nahi hai. Current official lists:
  - https://ai.google.dev/gemini-api/docs/models
  - https://ai.google.dev/gemini-api/docs/pricing

## Intended UI

1. **Agent** — clean chat composer, voice input, current action, Pause / Resume / Stop, live task logs.
2. **Automations** — task queue, recurring schedule, next run, last result, retries.
3. **Media** — chosen folder, video count, uploaded / pending / failed / needs-review filters, preview and metadata.
4. **Memory & Settings** — Gemini key/model, Accessibility status, overlay permission, battery guidance, learning/memory status, public publish permission.
5. **Floating Nax Bot HUD** — compact task-status indicator; it must not cover the active field or publish button.

Existing view IDs and working components will be preserved where practical. Each code change should be isolated and build-tested, rather than replacing the entire app blindly.

## Core execution design

Every automation will use a bounded state machine:

QUEUED → PREPARING → OBSERVING → PLANNING → ACTING → VERIFYING

Then one of:

- WAITING: page is loading or a normal wait is required.
- PAUSED: user paused the task or the agent needs human input.
- RETRYING: action failed and a safe bounded retry is allowed.
- SUCCEEDED: objective has screen-verifiable completion evidence.
- FAILED: non-recoverable problem.
- NEEDS_CONFIRMATION: result cannot be verified reliably.
- CANCELLED: user cancelled.

Only one task may control the physical screen at a time. Other independent work such as scanning files, preparing metadata or writing a report can run concurrently. Parallel touch tasks would interfere with one another.

## YouTube folder → public upload workflow

1. Ask the user to select a folder using Android's system document picker and persist the folder permission.
2. Scan supported video formats; show file name, size and last-modified time.
3. Match each item against local upload history. Use stable document identity plus file attributes; never rely on filename alone when an item can be replaced.
4. Reserve the selected queue item so two task workers cannot upload it at once.
5. Ask Gemini for a title, description and tags from the user prompt and available video evidence. Never claim to have watched/analyzed the whole video if only metadata or sampled frames were inspected.
6. Open the official YouTube upload flow. Use UI labels / Accessibility nodes first and calibrated coordinates only as a carefully bounded fallback.
7. Select the intended file, fill metadata, choose the user-configured visibility, and verify the visible values before publish.
8. If auto-public mode is disabled, pause for user's final review. If enabled, publish only after all pre-publish checks pass.
9. Verify a published/success state in the UI. If the app screen changed unexpectedly or confirmation is missing, mark NEEDS_CONFIRMATION and do not enqueue a blind duplicate retry.
10. Save task result, timestamp, video identity, generated metadata and evidence summary; display a report.

### Real-world Android limit

UI automation is not a cloud server. Android screen interaction requires the phone to be on, unlocked as needed, the Accessibility Service enabled and YouTube's UI accessible. A phone that is powered off cannot perform screen taps. Battery restrictions, login challenges, network delays and YouTube UI changes can pause a task. WorkManager/alarms can wake the app for eligible background work, but they cannot guarantee a successful visible upload with the display unavailable.

## Planned code ownership (file-by-file)

| Path | Responsibility |
|---|---|
| .github/workflows/build.yml | Build debug APK on pushes / manual Actions run and upload artifact |
| app/src/main/java/com/auradroid/agent/MainActivity.kt | Main screen, task control, settings and reports |
| app/src/main/java/com/auradroid/agent/GeminiBrain.kt | Model routing, intent/task planning and metadata generation |
| app/src/main/java/com/auradroid/agent/AutoTouchService.kt | Accessibility inspection, gesture dispatch and safe UI actions |
| app/src/main/java/com/auradroid/agent/PhoneAgentManager.kt | Observe → plan → act → verify loop, memory-on-stuck and recovery |
| app/src/main/java/com/auradroid/agent/VideoQueueManager.kt | Folder scan, persistent identity and uploaded/pending queue |
| app/src/main/java/com/auradroid/agent/NativeYouTubeUploader.kt | Starts the user-facing upload flow; no YouTube Data API upload |
| app/src/main/java/com/auradroid/agent/DailyYouTubeUploadReceiver.kt | Scheduled YouTube task entry point |
| app/src/main/java/com/auradroid/agent/TaskSchedulerReceiver.kt | Scheduled task triggers |
| app/src/main/java/com/auradroid/agent/AutomationReliability.kt | Recovery lessons, task-state persistence and verification helpers |
| app/src/main/res/layout/activity_main.xml | App UI layout; preserve IDs used by MainActivity unless both files change together |
| app/src/main/res/xml/accessibility_service_config.xml | Accessibility capabilities required for user-approved automation |
| app/src/main/AndroidManifest.xml | Services, permissions, receivers and app entry point |

New files will be added only where a separate responsibility improves testability. The exact file contents and creation path will be documented in the implementation guide whenever a change is made.

## Milestones and acceptance tests

### Phase 1 — Safe foundation
- Build through GitHub Actions on the feature branch.
- No API key committed to GitHub.
- Model ID editable from Settings.
- UI displays permissions and service state correctly.
- Stop button stops an active agent task.

### Phase 2 — Reliable agent loop
- Screen/action loop records action result separately from task success.
- No repeated identical action without a new observation.
- Retry count is bounded.
- Pause/resume/cancel/retry work without duplicating scheduled tasks.
- Memory retrieval runs only on a stuck/failure signal or explicit correction.

### Phase 3 — Video queue and metadata
- Folder permission survives app restart when Android grants it.
- Pending videos are stable across restart.
- Uploaded videos remain marked; renamed/replaced files are handled conservatively.
- Metadata is editable before use.
- UI verifies chosen file and populated fields.

### Phase 4 — Upload and report
- Dry-run/review mode works before enabling public auto-publish.
- Publish only under the user-authorized visibility mode.
- Success is reported only with visible evidence.
- Unexpected screen / login / CAPTCHA / permission prompt pauses safely.
- A completed task produces a timestamped summary.

### Phase 5 — More task types
Add generic task modules only after the common queue, state machine, permissions and reporting path pass tests. Multi-tasking means queue/orchestration and safe parallel preparation—not concurrent control of the same screen.

## GitHub APK download

1. Open the repository's **Actions** tab.
2. Open the run named **Build NaxBot AI Agent APK**.
3. Wait for the job to finish successfully.
4. In **Artifacts**, download **NaxBot-AI-Agent-Debug-APK**.
5. Extract the ZIP to get app-debug.apk.

The first build after each code change is the test gate. A green build confirms compilation only; phone-side testing is still needed for Accessibility, screen calibration and YouTube UI behavior.
