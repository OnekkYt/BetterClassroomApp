# Better Classroom for Android

An Android app that opens **Google Classroom** and runs the **[Better Classroom](https://chromewebstore.google.com/detail/better-classroom/nldhifdlnmmkgngbnnmkoppinhafblbi)** Chrome extension inside it, so the themes, pills, notes and other features you have on desktop work on your phone too.

> Unofficial. Not affiliated with, endorsed by or sponsored by Google. "Google Classroom" belongs to Google. The Better Classroom extension is a separate project and is **not** included in this repository.

## Features

- Opens `classroom.google.com` in a full-screen WebView and injects the extension's content scripts.
- Runs the extension's real service worker in a hidden WebView, so cloud sync, licences and badges behave as they do in Chrome.
- Updates the extension from the Chrome Web Store in the background. The app opens instantly. A newer version downloads quietly and is used the next time you restart the app.
- Switches the extension's mobile mode on automatically.
- Optional **auto-login** for when your session expires. The password is encrypted with a key held in the Android Keystore.
- Does not hand you over to the official Classroom app after sign-in.
- Small ⚙ button instead of a banner: extension settings, full options page, desktop-site toggle, smooth-scrolling mode, auto-login, update check, reload.
- Smooth-scrolling mode throttles the extension's scroll work and disables backdrop blur, which phone GPUs repaint on every frame.

## Install

1. Open the [Releases](../../releases) page and download `BetterClassroom.apk` from the latest release.
2. Allow installing from your browser or file manager if Android asks.
3. Open the app and sign in to Google as usual.

Requires **Android 7.0 (API 24)** or newer.

**Updating the app:** every release is signed with the same key, so a new APK installs over the old one and keeps your data. Releases are named by version, for example `v1.16`.

## Using it

Tap the ⚙ button in the corner:

| Item | What it does |
| --- | --- |
| Better Classroom settings | Opens the extension's settings panel on the page |
| Full options page | Opens the extension's options page |
| Desktop site | Switches between the mobile and desktop version of Classroom |
| Smooth scrolling (no blur) | Performance mode, on by default |
| Auto-login | Saves your password (email is optional) to sign in again when the session expires |
| Extension update | Checks the Web Store now |
| Reload | Reloads the page |

### Auto-login

When Google shows a sign-in page, the app presses the single Next/Continue button if the account is already shown, types the email only if the box is empty and you saved one, then fills in the password. It tries at most twice every two minutes so a wrong password cannot lock the account. Two-step verification and sign-in through an external single-sign-on provider are not handled. If your school signs you in through its own login page, auto-login may stop at that point.

Your password is stored encrypted on the device only and never leaves it. **Remove** in the auto-login dialog deletes it.

## How it works

Browser extensions need `chrome.*` APIs that a WebView does not have, so the app supplies them:

- `assets/shim.js` implements the parts of `chrome.storage`, `chrome.runtime` and a few other APIs that the extension uses. It runs in three places: the Classroom page, the extension's options page, and a hidden background page that hosts the service worker.
- `Hub.java` is the native side. It installs and serves the extension's files, backs `chrome.storage` with a JSON file, passes messages between the page and the background, performs network requests for the background page, and handles Web Store updates.
- `MainActivity.java` is the Classroom screen: user-agent handling, navigation rules, content-script injection at the right moments, the ⚙ menu and auto-login.
- `assets/autologin.js` is the script that fills the Google sign-in pages.

The extension's files are served from a private virtual host inside the app. Content scripts are injected in the page's main world at `document_start` and after load, each file separately so they share one global scope.

## Building

You need no Gradle. `build.ps1` calls the Android command-line tools directly (`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`).

**Prerequisites:** Windows with PowerShell, a JDK (17 is fine), and the Android SDK with build-tools and a platform (the script uses `ANDROID_HOME`, or `%LOCALAPPDATA%\Android\Sdk`).

**The extension is not in the repo.** Put its unpacked files in `assets/ext/` (so that `assets/ext/manifest.json` exists) before building.

```powershell
# normal build: bundles assets/ext and updates itself from the Web Store
./build.ps1

# raise the version first (1.16 -> 1.17)
./build.ps1 -Bump

# dev build: bundles your own extension zip and never contacts the Web Store
./build.ps1 -DevZip C:\path\to\extension.zip
```

The output is `BetterClassroom.apk` (or `BetterClassroom-dev.apk`) in the project folder. The version comes from `version.txt`.

The first build creates a local `debug.keystore`. Keep it safe and do not commit it (it is in `.gitignore`). Apps signed with a different key cannot be installed over each other.

### GitHub Actions

`.github/workflows/build.yml` builds the APK on every push to `main`, downloads the extension from the Web Store, and publishes a GitHub release tagged with the version in `version.txt`.

To use it on a fork:

1. Create a repository secret named `KEYSTORE_BASE64` containing your keystore, base64-encoded.
2. Under Settings → Actions → General, give workflows **read and write** permission.

The workflow can also be started by hand with a direct link to an extension zip, which produces a dev-build pre-release. Only do that in a private repository, because the zip ends up in the release.

## Project layout

```
AndroidManifest.xml
build.ps1                      build script
version.txt                    version (1.16 -> versionCode 116)
assets/shim.js                 chrome.* implementation
assets/autologin.js            Google sign-in helper
assets/ext/                    the extension (not committed)
res/                           launcher icon
src/com/bettercls/classroom/
    App.java                   starts the Hub
    Hub.java                   extension files, storage, messaging, updates
    MainActivity.java          Classroom screen and menu
    OptionsActivity.java       the extension's options page
    BaseActivity.java          shared WebView setup and file picker
    Creds.java                 Keystore-encrypted login details
```

## Limitations

- It is a WebView, not the official app, so push notifications and other native features of the official Classroom app are not available.
- Anything in the extension that depends on Chrome APIs the shim does not implement will not work.
- Google can change its sign-in pages at any time, which can break auto-login.

## Privacy

The app talks to Google (Classroom and sign-in), to the Chrome Web Store (extension updates), and to whatever servers the Better Classroom extension itself uses. It has no analytics or servers of its own, and it does not send your data anywhere else.

## License

No license has been chosen yet. Until one is added, all rights are reserved by the author.
