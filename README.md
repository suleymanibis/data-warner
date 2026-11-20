# Data Warner

Data Warner tracks mobile data use on Android. It shows how much data apps use, how much is shared through a hotspot, and how usage changes over time.

## Install

Download the signed APK for **Android 11 or later** from [GitHub Releases](https://github.com/suleymanibis/data-warner/releases/latest) or the [project page](https://suleymanibis.dev/apps/data-warner). Open the app and grant **Usage access**. Turn on notifications to receive usage alerts.

The default alert is **3 GB in one hour**. You can change both the limit and the time window. Use the **Data Meter** to measure a shorter session, such as the first five minutes of a stream.

Android reports hotspot usage as a single total. Its per-app and hotspot records can lag during short sessions. Usage records are stored on the phone.

## Build from source

The build script runs in Windows PowerShell. Install JDK 21, Android SDK Platform 36, and Android SDK Build-Tools 35.0.0, then run:

```powershell
.\build.ps1 -Sdk 'C:\path\to\Android\Sdk' -Jdk 'C:\path\to\jdk-21'
```

The script writes the APK to `build/Mobil-Veri.apk` and generates a local signing key on the first run. Use the same key for updates to your own build.
