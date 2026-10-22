# LIAN

**Nothing runs behind your back.** LIAN finds the apps that keep running when you aren't using them, or quietly
collect your data, and warns you about scam messages and links the moment they arrive. LIAN itself has no trackers,
no third-party code and **no `INTERNET` permission**, so Android guarantees that nothing you check ever leaves the
phone; the build fails if that ever changes.

**Status: 1.2.0.** A simpler LIAN: four tabs instead of five, a short Home that opens each feature, **Scam Shield**
explained in plain words, an Apps hub that's fun to explore, a friendlier "check before you install", and a flat,
Pixel-style logo animation (the I's dot drops onto the L). The QR scanner is gone, and with it the camera permission
and the ZXing library. Verified on an Android 15 emulator, light and dark.

## About this repository

This repository, **AI-Security-Guardian**, is Version 2.0 of our B.Tech project *AI Security Guardian* (2025). The
original app relied on cloud services; this version rebuilds it as LIAN, a privacy-first Android app in which every
check runs on the phone.

```bash
git clone https://github.com/raucous12/AI-Security-Guardian.git
cd AI-Security-Guardian
./gradlew test          # needs a JDK 17+ and the Android SDK (see "Build and test" below)
```

Open the folder in Android Studio, or create `local.properties` with `sdk.dir=<path to your Android SDK>`, so Gradle
can find the SDK. On Windows, use `gradlew.bat` instead of `./gradlew`.

Maintainers: [@raucous12](https://github.com/raucous12) and [@likhitha220712](https://github.com/likhitha220712).

## Navigation

| Tab | What it does |
|---|---|
| **Home** | A short overview, not a dashboard: one line saying whether anything needs you, then one card per feature (Scam Shield, Your apps, Phone health) that opens it. The promise links to the privacy proof. |
| **Scam Shield** | **Checks the messages you get, and every link in them, for scams**, automatically (notification access): SMS, WhatsApp, Telegram, Signal, email and more, on the phone, as they arrive. The tab says what it does in three steps ("a message arrives, LIAN checks it on your phone, you're warned before you tap") with concrete examples (fake delivery fees, account-blocked threats, "Hi Mum, new number", requests for codes, look-alike links). Alerts name the app but never quote the message; warnings live in memory for 24 h. A manual check covers anything else. |
| **Apps** | An animated ring of apps that look fine / are worth a look / are risky, plus how many ran unseen today; search; filter chips (Worth a look, Run unseen, See your screen, Location always, Changes, All); rows with each app's real icon and little coloured icons for what it can reach. Tapping an app opens a sheet: what it can access, background activity hour by hour, red flags and good signs, and the settings shortcut. **Check before you install**: pick or share an APK and get a verdict sheet ("No red flags" / "Install with care" / "Don't install this"), what it would access, red flags in plain words, technical details folded away. |
| **Settings** | Appearance (System / Light / Dark, optional wallpaper colours), Scam Shield, usage access and Watch switches, **Privacy proof**, hide-from-screenshots, delete all data, about. |

**Phone health** (opened from Home) holds the device-health dial and the 13 checks, with what to fix first.

No-friction entry points: **"Check with LIAN"** in every app's text-selection menu, share-to-LIAN (text and APKs),
launcher shortcuts, and the **install guard** (each newly installed app is checked while Scam Shield is on).

## Features

| Feature | What it does |
|---|---|
| **Working unseen** | Android's own usage records (usage access, granted by the user) show foreground-service time versus on-screen time per app. Rules flag hidden workers (no launcher icon), apps collecting personal data while unseen (location, mic, camera, contacts, SMS, call log, notifications, screen), screen readers, always-on location, boot starters and battery-unrestricted apps. System components are excluded. |
| **Device check-up** | 13 checks with a 0–100 score: Android version support, security-patch age, screen lock, **boot state and bootloader lock read from secure hardware** (key attestation verified against Google's roots and revocation list), signs of root, storage encryption, user-installed CAs, USB / wireless debugging, developer options, Private DNS, VPN, Advanced Protection. Unreadable items show as *Unknown* and never change the score. |
| **App audit** | Every installed app: where it really came from (tamper-resistant initiating installer; fake-store detection), active special access (Accessibility, device admin, notification access, keyboard, SMS, overlay, usage access, background location), **known stalkerware and monitoring apps** (Echap indicators: 158 products, 532 packages, 371 certificates), impostor apps using a brand's name, hidden apps with monitoring powers, banking-trojan power combinations, developer builds. A disclosure screen comes first; a critical app gets a safety warning before the settings shortcut. |
| **Message & link check** | Automatic (Scam Shield) or pasted, shared or selected text. The **wording** is checked (requests for one-time codes, gift cards, delivery/toll fees, account threats, "Hi Mum, new number", arrest threats, prizes, crypto lures, urgency) and **every link** is checked without opening it (look-alike characters, misspellings, brand-plus-words, disguised IPs, hidden characters, shorteners, redirects, risky endings, premium numbers, stalkerware vendor sites). |
| **APK check before installing** | Pick or share an APK. Parsed by LIAN's own pure-Kotlin reader **in an isolated, permission-less process**: manifest, label, permissions, declared takeover services, and **APK Signature Scheme v2/v3 verified** including the whole-file digest. Flags stalkerware, impostors, updates signed by someone else, broken signatures, trojan permission combinations, hidden icons. |
| **Watch** | Optional daily background re-check (JobScheduler). Alerts on new risky apps, apps gaining special access, apps hiding their icon, signer changes, new CAs and settings that got worse. Stores only HMAC fingerprints (never the app list); notifications are always generic. |
| **Privacy proof** | Live evidence from Android: no INTERNET permission, a real socket refused by the kernel (EPERM), the file-checker sandbox (isolated UID, can't read LIAN's files), permissions, installer, signing certificate. |

## The machine-learning layer

Two small logistic-regression models (`message.vgm`, `domain.vgm`, 256 KB each) run in pure Kotlin on the phone.
They were trained offline by `:tools:trainer` from public datasets pinned by SHA-256 (UCI SMS Spam Collection and
Mishra & Soni smishing dataset, CC BY 4.0; Phishing.Database, MIT; Majestic Million, CC BY 3.0).

| Model | Test AUC | Strong level: precision / recall / false positives |
|---|---|---|
| Message wording | 0.995 | 1.00 / 0.92 / 0.0 % |
| Link domain name | 0.870 | 0.97 / 0.43 / 0.4 % |

Design rules (Phase 2's R1 amended at the user's request; the privacy parts are unchanged):
- **No LLMs, no cloud, no third-party ML runtime, no native code.** Gate C bans TFLite/ML Kit/ONNX/PyTorch/MediaPipe
  imports; Gate D fails the build on any third-party model format and on any `.vgm` file not explicitly allow-listed.
- **Explainable and bounded.** Each model hit is one finding listing the words or domain parts behind it. The two
  models share a correlation group, so on their own they can raise a result to *Be careful* but never further
  (enforced by a unit test).
- **Deterministic and reproducible.** 8-bit weights and binary features make the score an exact integer sum;
  retraining from `tools/trainer/corpus/` gives byte-identical files (checked).
- **Integrity-checked** like all bundled data (SHA-256 manifest), and **no learning on the phone**.

Full details, data, metrics and limitations: [docs/ml-model-card.md](docs/ml-model-card.md).

## Modules

```
:core:engine    pure Kotlin/JVM, deterministic: facts in → findings out
                (links, messages, ML inference, apps, APK parser + signature verifier, device posture, attestation, Watch diff)
:core:intel     bundled data + integrity-checked loader (PSL, confusables, brands, stalkerware, stores, OS support,
                attestation roots and revocations, the two models)
:core:platform  the only module that calls Android system APIs (device probe, app inventory, background probe,
                privacy probe, isolated UntrustedContentService for APKs)
:core:storage   Keystore keys, sealed files, HMAC fingerprints (Watch)
:app            Compose UI (LIAN design system, animated logo), Scam Shield listener, shortcuts,
                Watch job and notifications
:tools:trainer  maintainer tool: fetch the pinned datasets, train the models, write the model card (never shipped)
build-logic     the privacy gates as Gradle plugins
```

## Privacy gates (enforced on every build)

| Gate | Checks |
|---|---|
| **A** | Merged manifest: only the 7 reviewed permissions below; exported: the launcher activity plus one service only the system can bind (Scam Shield listener); backup off, deny-all network config |
| **B** | Every runtime dependency from a reviewed group: Kotlin and AndroidX only (ZXing went with the QR scanner in 1.2) |
| **C** | Sources: no sockets/HTTP/WebView/dynamic code/ML runtimes/weak crypto/logging; engine also bans clocks, randomness and locale-dependent calls. Reviewed exceptions are marked `privacy-gate: allow <rule>` |
| **D** | Packaged APK: no third-party model files, only the two allow-listed `.vgm` models; native libraries 16 KB-aligned |

Permissions (none grants network access): `QUERY_ALL_PACKAGES` (app audit; Play declaration as a security app),
`ACCESS_NETWORK_STATE` (VPN / Private DNS status), `QUERY_ADVANCED_PROTECTION_MODE`, `POST_NOTIFICATIONS` (asked
only when Watch is turned on), `RECEIVE_BOOT_COMPLETED` (keeps the Watch job), `PACKAGE_USAGE_STATS` (working unseen; only the user can grant it, in Settings), and AndroidX's internal
signature-level permission. Notification access for Shield is likewise granted only by the user, in Settings. Verified negative tests: `INTERNET` in the manifest, `Class.forName` in a source file,
a `.tflite` file or an unlisted `.vgm` file each fail the build.

## Verification (1.2.0)

- 139 JVM unit tests (engine incl. background rules, intel incl. official PSL vectors and bundled-model behaviour,
  storage, app incl. Scam Shield message extraction), all passing; the engine suite runs in a Turkish locale.
- 8 on-device tests on Android 15: no INTERNET, kernel refuses sockets, only the launcher (and the system-bound Scam
  Shield listener) exported, APK parser agrees with PackageManager and verifies the v2/v3 signature, the isolated
  process really is isolated, hostile file refused, collectors on a real system.
- Emulator walkthrough, light and dark: the logo animation frame by frame, Home, Scam Shield, the Apps hub and detail
  sheet, and an APK picked through the system file picker, checked in the sandbox and shown as a verdict sheet.
  The minified release build was exercised across all tabs.
- Lint: no issues. All four privacy gates pass.

### 1.0.0
- 8 on-device tests on Android 15: no INTERNET, kernel refuses sockets, only the launcher exported, APK parser agrees
  with PackageManager on the real APK (and verifies its v2/v3 signature), isolated process really isolated, QR decode
  in the sandbox, hostile file refused, collectors on a real system.
- Lint: no issues. Release build (R8) installed and exercised on the emulator.
- Measured on the emulator (debug build): app inventory of 245 apps 0.97 s cold / 0.5 s warm, device facts 1.2 s.

## Build and test

Requires JDK 17+ (Android Studio's bundled JBR works). Toolchain: Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20,
compileSdk 37, targetSdk 36, minSdk 30.

```bash
./gradlew test                                   # 139 JVM tests
./gradlew :app:lintDebug                         # lint, warnings as errors
./gradlew :app:assembleRelease                   # R8-minified, all gates (unsigned without an upload key)
./gradlew :app:assembleRelease -Plian.signReleaseWithDebugKey=true   # installable test build (Play rejects it)
./gradlew :app:connectedDebugAndroidTest         # on-device tests
./gradlew :tools:trainer:trainModels             # retrain both models from tools/trainer/corpus (offline, reproducible)
```

`dist/` (local only, not committed) holds the latest built deliverables: an installable release APK signed with the
SDK debug key (for testing only), the unsigned Play bundle, and their SHA-256 sums. Built files are attached to GitHub
releases rather than stored in the repository.

## Updating the bundled data and models

```bash
./gradlew :core:intel:updateIntel                                   # PSL, confusables, stalkerware, attestation revocations
./gradlew :core:intel:updateIntel -Pintel.acceptNewHashes=true      # after reviewing an upstream change
./gradlew :tools:trainer:fetchCorpus -Pml.acceptNewHashes=true      # refresh training data (Majestic changes daily)
./gradlew :tools:trainer:trainModels                                # retrain; rewrites docs/ml-model-card.md
```

Curated files (`brands.tsv`, `stores.tsv`, `os_support.tsv`, `tld_risk.tsv`, `shorteners.txt`, `premium_numbers.tsv`,
`script_tlds.tsv`, `attestation_roots.pem`, `tools/trainer/corpus/authored-messages.tsv`) are edited by hand and
reviewed in pull requests.

## Before a Play release

- Set `lian.applicationId` in `gradle.properties` to the reverse of a domain you control (permanent on Play).
- Provide the upload key through `UPLOAD_KEYSTORE`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`
  and build `bundleRelease`; after the first internal-track upload, set `lian.expectedSigners`.
- Submit the `QUERY_ALL_PACKAGES` declaration (text in the Phase 5 notes); Data safety: no data collected or shared.
- Describe Shield's notification access and the usage-access feature in the listing and the in-app disclosures
  (prominent disclosure before the user is sent to Settings; both are already explained on the onboarding and tab screens).
- Store listing: the app no longer claims to be "AI-free"; say "on-device, offline models; no cloud AI".
- Measure release performance on a real mid-range phone (Macrobenchmark).

## Licences of bundled data

Public Suffix List (MPL-2.0) · Unicode confusables (Unicode License v3) · Stalkerware indicators by Echap (CC BY 4.0) ·
Google attestation roots and status list · Model training data: UCI SMS Spam Collection (CC BY 4.0), Mishra & Soni SMS
Phishing Dataset (CC BY 4.0), Phishing.Database (MIT), Majestic Million (CC BY 3.0) · Material icons (Apache-2.0).
Sources, authors and the changes made to each dataset are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Licence

The source code is released under the [MIT License](LICENSE). Bundled data files and the training corpus keep their
own licences, listed above and in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
