# DnD Keyword Override

Flutter app that monitors incoming notifications while **Do Not Disturb** is active.
If a notification's title or body contains any user-entered keyword (case-insensitive),
it overrides DnD and plays the default ringtone, vibrates, and wakes the screen.

Built for Xiaomi HyperOS 3, where starred-contact messages are not detected in DnD.

## Requirements
- Flutter SDK (tested with 3.0.5)
- Android SDK (platform 21+) with build-tools
- A device/emulator running Android 5.0+

## Build
1. Set `flutter.sdk` and `sdk.dir` in `android/local.properties`:
   ```
   flutter.sdk=E:\\Apps\\Flutter
   sdk.dir=C:\\path\\to\\android-sdk
   ```
2. `flutter pub get`
3. `flutter build apk --release` (or `flutter run`)

## First run on device
1. Open the app.
2. Tap **Grant** next to "Notification access" and enable this app in the system list.
3. (Optional) Grant DnD policy access.
4. Tap **Fix** for battery optimization to whitelist the app.
5. In HyperOS Security/Battery settings, enable **Auto-start** and disable battery restrictions for this app.
6. Add keywords (e.g. "Mom", "urgent"). Enable DnD. Incoming matching notifications will ring.

## Notes
- Matching is case-insensitive "contains" on title + body + big text + messaging-style messages.
- The service only acts while a non-ALL interruption filter (DnD/priority) is active.
- Dedup prevents repeated triggers within 5 seconds for the same notification.
