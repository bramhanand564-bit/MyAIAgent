# MyAIAgent — Master Memory

## Core Goal
Build a mobile-first Android AI agent for user-authorized YouTube automation.

Primary workflow:
Phone files/folders → select/import video → queue → metadata → schedule → open official YouTube/YouTube Studio → perform real Android UI automation → verify every action → monitor upload → record result → continue to next item.

### First Milestone
One video → Run Test Now → complete the real YouTube upload workflow.

Scheduling is secondary. Development/testing must be possible immediately without waiting for a scheduled time.

## YouTube Automation Requirements
- Use the official YouTube/YouTube Studio Android app surface.
- Do not use YouTube Data API for this upload workflow.
- Use Android AccessibilityService for real UI control.
- Prefer live accessibility nodes/labels and verified targets over fixed coordinates.
- Exact queued filename must be selected; never choose another visible file.
- Verify every important transition before moving to the next state.
- Processing alone is NOT upload success.
- Success requires real upload/publish evidence.
- Login, CAPTCHA, security checks and verification screens must pause and wait for the user.
- Never bypass security, CAPTCHA, quota, rate limits or account protections.
- Bounded retries and session watchdogs are required.

## Core State Flow
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

Optional states:
WAITING_USER / ERROR

## AI Agent Direction
NAX should evolve from a fixed YouTube state machine into a reusable:
Observe → Understand → Act → Verify → Learn agent.

AI/vision is a reasoning and fallback layer. It must never invent success.

Learning rule:
- UI actions start as pending candidates.
- A candidate becomes learned only after the state transition is actually verified.
- Failed/ambiguous/unverified actions are never promoted as reliable memory.
- Learned UI knowledge is only a hint; current-screen verification remains mandatory.

## Reliability Model
1. Observe the current package/UI.
2. Detect interruptions/security states.
3. Locate the live target.
4. Perform a real Accessibility action/gesture.
5. Observe the new screen.
6. Verify the expected transition.
7. Learn only from verified success.
8. Recover with bounded retries when safe.
9. Pause for manual user action when required.

The system must remain flexible across:
- different Android pickers
- different browsers/apps
- different keyboards
- changing labels/layouts
- temporary UI interruptions

Do not depend on one package, one coordinate, one keyboard or one exact CAPTCHA string.

## Product Direction
- Mobile-first.
- GitHub/mobile development.
- Main branch only.
- Clean, professional, polished UI.
- Separate pages for major functions.
- Real actions only; UI must never claim an action happened when it did not.

## Future Scope
- Batch queue and scheduling.
- More reliable picker/file handling.
- Upload completion/public availability verification.
- AI-assisted workflow recovery.
- Reusable Android agent core for workflows beyond YouTube.
- Additional user-authorized automation workflows.

## Current Reset Decision — October 2026
The previous Android implementation accumulated too many unreliable/experimental files.

The repository is intentionally being reset to a memory-first state:
- Keep this memory.md as the project master specification.
- Remove the old Android source/build implementation.
- Remove the old GitHub Actions Android build workflow.
- Do not treat old APK/build artifacts as the current implementation.
- Future implementation will be rebuilt cleanly from this master memory.

## Non-Negotiable Rules
- Never blindly tap when a verified accessibility target exists.
- Never select a different file because it is visible.
- Never mark Processing as success.
- Never claim an automation is active without a real active session.
- Never bypass CAPTCHA/security/login verification.
- Never introduce YouTube Data API upload automation.
- Keep project changes on main.
- Prefer fixing the observed root cause over adding random retries.

## Auto Tapper — Milestone 1 (October 2026)
- Added Apple-inspired mobile UI prototype in `autotapper-ui.html`.
- Added clean Android app foundation under `app/`.
- Main controls: X/Y screen point, interval (50–5000 ms), start delay (0–30 s), duration (0 = unlimited), START, STOP, RESET, live tap counter.
- Target preview lets the user choose a point; the app converts the normalized preview position to the physical display dimensions.
- Real tapping engine uses Android `AccessibilityService.dispatchGesture()` after the user explicitly enables the service.
- Service supports repeated taps, interval scheduling, duration cutoff, tap counting, STOP, and interruption cleanup.
- CI workflow added at `.github/workflows/build.yml` to build a debug APK and upload it as an artifact.
- This module is user-authorized automation only. It must not bypass CAPTCHA, login/security verification, rate limits, or account protections.
- Next Auto Tapper upgrades: draggable floating on-screen marker for external apps, multi-point sequences, long-press, per-point delays, repeat cycles, random jitter/interval, presets/history, package restriction, foreground-state verification, emergency stop.
