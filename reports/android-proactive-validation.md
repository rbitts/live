# Android proactive + memory assistant — 2026-10-13

## Scope and design

User selected a combination of scene-change awareness and remembered user conditions. Version0.7 adds a bell dialog, an opt-in observation loop and real model-fed session memory. This remains an experimental system-duplex application, not accepted Live performance. No model/upstream revisions or workstation GPU allocation changed. Physical device is SM-X926N/API36, MediaTek MT6989 with OpenCL VLM; ASR/TTS remain CPU.

Memory retains at most4 bounded conversational pairs,4 timestamped scene summaries and one explicit160-character condition. It shares the selected1/5/15-minute retention and is erased on opt-out/session end/background. Current images and previous observations remain separately labeled. General assistant answers use a new prompt, while the PC spatial benchmark retains its original deterministic no-image policy. Passing PC policy tests is not validation of the new assistant prompt.

The loop uses a single newest frame, at most one queued inference owner,15-second cadence,3-second input freshness and12-second result age. It skips user speech, user inference and audio playback; user interruption invalidates the epoch and late results. No observation backlog. A task needs two separate positive observations and produces one notice; scene-only mode has a silent baseline, important-change flag and45-second cooldown. This is scene interpretation by the VLM, not reliable object tracking.

Autonomous output goes to a chat bubble, unread badge and temporary toast; speech is optional. The current TTS remains slower than playback, so spoken proactivity can lag the text. Tasks are explicitly entered through the bell; voice task registration, timed reminders, app actions, durable cross-session memory and goal/progress planning are not implemented.

## Evidence so far

`proactive-run.log`: real device model PASS for negative condition(no purple elephant), positive condition(cup) after20.460s, single notice, duplicate suppression and recall of the cup task with camera off. Physical camera freshness was separately checked; autonomous judgment used controlled GQA frames. The initial notice was overly generic(“조건 충족”). This is not a human live demonstration.

`proactive-final.log`/`proactive-diag.log`: follow-up prompt asking for a specific notice exposed schema drift. The model emitted `"match":"true"` rather than JSON boolean; strict parsing correctly withheld the notice. Exact payload inspected. The parser now normalizes only literal `"true"`/`"false"`; other strings/numbers/malformed schemas still fail closed. The expanded run below separately checks scene change and conversation recall.

Local AssistantMemory/ObservationMemory tests cover double confirmation, repeated-frame rejection, baseline silence, cooldown, stale results, cancellation-by-state, expiration, bounds and opt-out. Python45 and native UTF/evidence/epoch tests pass. ARM64 assemble/lint pass. Fresh-frame camera check is not camera recognition accuracy, acoustic echo or sustained thermal acceptance.

## Product reference requested by user

The user asked to exclude proactive screen-AI cases and look at Gemini Live demos. Useful official examples are goal-oriented marathon support, personal episodic recall, continuing an interrupted repair task, incremental drawing guesses and turning live discussion into a task/list. Research prototypes must be distinguished from shipped Gemini Live functionality; reactive recall is not itself proactive intervention.

- Google DeepMind marathon tester demo(2024-12-11): https://www.youtube.com/watch?v=lEvZXoFDmg4
- Creative/personal memory demo(2024-12-11): https://www.youtube.com/watch?v=rL6y0_X0muM ; official public transcript https://www.linkedin.com/posts/googledeepmind_project-astra-early-access-demo-using-ai-activity-7273352329750265856-Zq6o
- Repair/interruption demo(2025-05): https://www.youtube.com/watch?v=JcDBFAm9PPI ; official transcript https://www.linkedin.com/posts/googledeepmind_project-astra-activity-7331687695917666304-8Pp9
- Pictionary I/O demo(2024-06-05 report): https://blog.google/innovation-and-ai/products/new-google-ai-product-demos-io/
- Live conversation→Calendar/Tasks/Keep examples(2025-08-20): https://blog.google/products-and-platforms/products/gemini/gemini-live-updates-august-2025/

These sources were checked2026-10-13. Proposed app direction(inference, not an implemented feature): a task companion that remembers the goal, constraints and completed steps, resumes after interruptions, and volunteers a short useful intervention when progress warrants it. This requires task state and intervention policy beyond the current object-condition prototype.

## Expanded real-model result and remaining defect

`proactive-report.json` / `proactive-normalized.log`: scripted checks PASS, seven real-model observations. Physical camera delivered a fresh frame; no-purple-elephant stayed silent; cup task alerted once after21.473s; repeated matches did not duplicate; task recall worked without a camera. Switching from GQA2316817(two people holding glasses) to EPIC VISORP01_03_frame_0000003060(table with cups/containers) produced “이전 관측에서 사람이 사라졌습니다” and retained both timestamped summaries. These are dataset fixtures, not a person completing a live activity.

Manual review found a material limitation not caught by the keyword-based harness: after the user said their cup was blue, the no-camera answer recalled blue but incorrectly attributed it to “현재 제공 관측”. Thus conversation value recall passed, **source attribution failed**. The scene summary also used “목탁” for the table. Do not claim grounded general memory accuracy from the scripted PASS. The new assistant prompt with these spatially adapted weights needs a broader attribution/abstention evaluation and possibly a separately validated general-assistant model path; changing playback timing cannot solve this.

Final source includes a follow-up lifecycle fix that clears diagnostic observation/error strings alongside session memory; late diagnostic callbacks are epoch-guarded. ARM64 build/lint and Python45 passed again. APK `live-0.7-arm64.apk`, SHA256a5831c2bddf1708f1c5d6b6d2484b99b44fdc2660f3f5d24ae3afbc246d7c4c9; signing and16KB zip alignment verified. This is an opt-in experimental build, not full acceptance.
