# BLACKAI Android Sensor Emergency Engine

## Current repository finding
The current BLACKAI repository is a GitHub Pages web application built around `index.html`. A browser page cannot provide reliable, continuous background sensor monitoring when the page is closed or the phone is locked. This feature therefore requires a separate native Android application (Kotlin) or a properly maintained Android application wrapper with native code. Do not describe the existing website sensor dashboard as an active detector.

## Goal
Build an opt-in safety assistant that can recognize limited, observable distress signals and help the user request assistance. Sensors cannot determine that rape, kidnapping, or another crime has occurred. A detected pattern must be described as a *possible emergency*, never as proof of a crime.

## Proposed phased implementation

### Phase 1 — Native Android foundation
- Create a separate Android/Kotlin app project; keep the existing GitHub Pages website working independently.
- Add an explicit onboarding flow explaining what is monitored, when it runs, what is sent, and how to stop it.
- Require the user's affirmative opt-in. Show a persistent foreground-service notification while motion monitoring is active.
- Use only permitted motion sensors (accelerometer and gyroscope) for the first prototype.
- Do not silently activate the microphone, camera, or location. Add each permission only with a separate, clear explanation and user choice.

### Phase 2 — Conservative event detection
- Detect only narrow sensor patterns that can be tested (for example, a possible fall/impact followed by an unusual inactivity interval).
- Treat every result as uncertain. Normal activities such as running, travel, sports, or dropping a phone can look similar.
- Use a visible, configurable check-in/countdown and an easy cancel action before notifying trusted contacts, unless the user has separately configured a clearly explained emergency workflow.
- Never infer kidnapping or sexual assault from motion, sound, location, or phone inactivity alone.

### Phase 3 — Alert delivery
- First notify the user's chosen trusted contacts, only with prior consent and configured contact details.
- Include the event time, confidence/trigger category, and only the minimum location/context the user has explicitly authorized.
- Record delivery status and failures. Do not display “sent” unless the server/provider confirms it.
- Do not send automatic police reports or claim police integration until the relevant government/police authority has provided written authorization, production credentials, an approved endpoint, and end-to-end acceptance testing.
- In an immediate emergency, keep a clear path for the user or a trusted contact to call India's emergency number 112. The app is not a replacement for emergency services.

## Privacy and security requirements
- No covert monitoring; no hidden microphone/camera capture.
- Data minimization, encryption in transit and at rest, strict access controls, retention limits, and a user-visible stop/delete control.
- Protect against false alerts, duplicate alerts, spoofed sensor events, battery exhaustion, network loss, and device shutdown.
- Never expose API keys or police credentials in the Android client or public repository.

## Acceptance tests before any real-world use
1. Monitoring starts only after explicit opt-in and shows its persistent notification.
2. User can pause/stop monitoring and revoke permissions.
3. Everyday movement does not trigger an emergency alert in the test suite.
4. Simulated test events are clearly marked as tests and never contact real emergency services.
5. Trusted-contact alerts require configured consent and show confirmed/failed delivery accurately.
6. Network loss, app restart, reboot, battery saver, and permission revocation are tested on supported Android versions.
7. No police dispatch is enabled until official authorization and production validation are complete.

## Current status
**Planning specification only — no Android sensor service is implemented or active yet.** The next engineering task is to create and test the separate native Android/Kotlin project.