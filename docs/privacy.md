# Privacy

**This application works offline. It is designed for DeGoogled Android devices and focuses on providing a dashboard for EUCs without bloatware features, focusing on privacy and effectiveness.**

- NO GPS data
- NO Trackers
- NO ads
- NO data collection

Core BLE, dashboard and logging features work offline. Upstream release builds
use Internet permission to check GitHub's latest stable release at startup, at
most once per five minutes (including failed or cancelled attempts). There is no
periodic background polling; restarting the app after that interval allows a
fresh check, even if the previous attempt failed. GitHub receives the network
request, including your IP address; no ride data is uploaded. Network failures
do not show a popup and are recorded through the app's standard logging. A newer
stable version offers **Download** (opens the release APK
in your browser) or **Later**; TuxWheel does not download or install APKs itself.
Debug builds do not check for release APKs with a different application ID.
Source/help links open in your chosen browser; sharing opens your chosen app.
Automatic Android cloud backup and device-transfer extraction of app data are
disabled. This does not delete existing backups or protect public Downloads
files from other apps, device-owner tools, or a user's own backup software.
Export any data you need before uninstalling: settings will not restore
automatically.

## Distribution channels and update checks

Build with `-PenableGithubUpdates=false` to disable GitHub checks and remove
Internet permission, including library declarations. The default is `true`.
**F-Droid and other independently signed distributions must set this property**
for every build, including APKs that users might sideload. For example:
`./gradlew :app:assembleRelease -PenableGithubUpdates=false`.
Use the same property when running tests/lint for that channel.

Installer detection additionally suppresses checks for the official F-Droid
client/privileged installer and several alternative F-Droid clients. It is only
a fallback: sideloads can have no installer identity, and unknown clients cannot
be reliably identified. There is no upstream signing-certificate pin; Android
enforces signing compatibility at installation, but the build flag is essential
to avoid offering incompatible upstream APKs to independently signed builds.

Debug builds log to logcat, not automatically to public HTML files. Ride CSV
logging and optional raw BLE diagnostics remain explicit app features; they can
contain identifying data and should be disabled when not needed. Existing files
are not deleted by this cleanup.
