# BLACKAI Android Sensor Safety — prototype

This is a separate native Android starter project. It does not change the existing GitHub Pages website.

## What it currently does
- Starts motion monitoring only after the user taps the consent button.
- Runs an Android foreground service with an ongoing notification.
- Watches accelerometer readings for a deliberately simple high-impact threshold.
- Updates the persistent notification when a possible impact-like event is observed.

## What it does NOT do
- It does not detect kidnapping, rape, assault, or any crime.
- It does not record audio/video or track location.
- It does not message trusted contacts or contact police.
- It does not guarantee monitoring while force-stopped, powered off, or under all battery-management conditions.
- The threshold is an unvalidated prototype and may produce false positives or miss real emergencies.

## Build
Open this directory in Android Studio, allow Gradle sync, then run on a physical Android device. Android 13+ asks for notification permission. Android 14+ may apply additional foreground-service policy restrictions; review current Play Console requirements before distribution.

## Next steps before real-world testing
1. Build and test on supported devices and Android versions.
2. Add an accessible in-app check-in/cancel flow and carefully validate false-positive rates.
3. Add trusted-contact alerts only after explicit configuration and consent.
4. Add location only with a separate permission and clear user control.
5. Keep police dispatch disabled until written authorization, official production credentials, and end-to-end acceptance testing are complete.
