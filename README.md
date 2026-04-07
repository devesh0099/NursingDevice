# NursingDevice

Android app for the nurse's field device. Nurses register/login, scan a patient from the Aggregator via NFC, fill a vitals form (by voice or manually), and send the record back to the Aggregator via NFC.

## Prerequisites

- Android Studio (latest stable)
- Physical Android device with NFC, Android 11+ (API 30+)
- Aggregator app running on a second device
- Nursing-Backend running (local or on Render.com)

## Build & Run

```bash
# From the NursingDevice/ directory:
./gradlew assembleDebug     # build APK
./gradlew installDebug      # build + install to connected device
./gradlew test              # unit tests
```

Or open `NursingDevice/` in Android Studio → press **Run ▶**.

**Enable USB Debugging on your phone:**  
Settings → About Phone → tap Build Number 7 times → Developer Options → USB Debugging ON

| Setting | Value |
|---------|-------|
| minSdk | 30 (Android 11) |
| compileSdk | 34 (Android 14) |
| Language | Kotlin, Java 1.8 |

## First-Time Setup

1. Make sure the Nursing-Backend is running (Render.com or local)
2. Install the app on Phone 1
3. Open the app → you'll see the **Nurse Authentication** screen

## Nurse Authentication Screen

Two modes — toggled by the "New nurse? Register here" link:

**Login mode (default):**
- Enter your Nurse ID → tap **Login**
- Checks local cache first (works offline if you've logged in before)
- If not cached, verifies against backend (`GET /api/nurses/:nurseId`)

**Register mode:**
- Fill Nurse ID, Full Name, Age, Gender, Point of Care, Contact No
- Tap **Register Nurse** → calls `POST /api/nurses/register`
- If backend is unreachable, saves locally so you can still use the app offline

## Workflow (after login)

```
MainActivity shows: "Nurse: <name> | No Patient Scanned"

1. Tap "Scan Patient Tag"
   → ReaderActivity opens, NFC reader mode enabled
   → Hold near Aggregator → 3-step auth handshake → patient JSON received
   → SessionCache populated with patient name, age, gender, blood type, ID
   → MainActivity now shows: "Nurse: <name> | Patient: <patient name>"
   → "Update Patient Record" button becomes active

2. Tap "Update Patient Record"
   → SendForm opens with patient identity fields locked (auto-filled from SessionCache)
   → Nurse ID is auto-filled from NursePatientManager
   → Fill: Blood Pressure, Heart Rate, Respiratory Rate, Temperature, Medication, Description
   → Tap each field's mic icon or "Start Voice Input" button for voice entry
   → Medication field: voice accumulates entries until you say "stop"
   → Tap "Generate Record" → previews the .txt file
   → Tap "Send via NFC" → SendDocumentActivity opens

3. Tap "Send via NFC" → hold near Aggregator
   → MyHostApduService acts as NFC card, Aggregator reads the file
   → Record saved on Aggregator under NursingDevice/yyyy-MM-dd/
```

## Record File Format

`SendForm` writes a `.txt` file to `cacheDir` with this exact format:
```
MEDICAL UPDATE RECORD
==================

Nurse ID: NURSE_001
Patient Name: John Doe
Blood Pressure: 120/80
Heart Rate: 72 bpm
Respiratory Rate: 16 breaths/min
Body Temperature: 98.6F
Medication: Paracetamol 500mg
Description: Patient is stable

==================
Updated on: 07/04/2026 14:30:00
```

The Aggregator's `SyncRepository.parseToRecordRequest()` parses this format when syncing to the backend. **Do not change the prefix strings** (e.g. `"Nurse ID: "`, `"Blood Pressure: "`) without also updating that parser.

## Key Source Files

| File | What it does |
|------|-------------|
| `AuthActivity.kt` | Nurse login/registration. Calls `NurseRepository` for backend auth. Falls back to local cache if offline. |
| `NurseRepository.kt` | Retrofit client for `POST /api/nurses/register` and `GET /api/nurses/:nurseId`. |
| `NursePatientManager.kt` | SharedPreferences store for the logged-in nurse (`nurseId`, `name`) and received patient JSON. |
| `SessionCache.kt` | In-memory singleton for the current NFC session. Holds patient name, age, gender, blood type, ID. Cleared on `clearSession()`. |
| `ReaderActivity.kt` | Puts device in NFC reader mode. Drives the 3-step auth handshake with Aggregator. Calls `SessionCache.processScannedData()` on success. |
| `MyHostApduService.kt` | NFC HCE background service. Responds to Aggregator's reader with the medical record file. States: `IDLE → AUTHENTICATED → READY_TO_SEND → SENDING`. |
| `SendForm.kt` | Vitals entry screen. Auto-populates nurse ID and patient identity. Writes the `.txt` record file. |
| `CryptoUtils.kt` | RSA-2048 encrypt/decrypt/sign/verify, AES-128 session key generation, XOR stream cipher. |
| `Utils.kt` | NFC AID (`F0 01 02 03 04 05 06`) and APDU command byte constants. Must stay in sync with Aggregator's `Utils.kt`. |

## Permissions Required

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-feature android:name="android.hardware.nfc.hce" required="true" />
```

## Dependencies

```kotlin
// core
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.11.0")
// networking
implementation("com.squareup.retrofit2:retrofit:2.9.0")
implementation("com.squareup.retrofit2:converter-gson:2.9.0")
implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
```

## Logcat Tags

```
AuthActivity        nurse login/register flow
NurseRepository     backend HTTP calls
MyHostApduService   NFC HCE events (sending record)
ReaderActivity      NFC reader events (receiving patient)
SessionCache        patient data parsing
```

## Common Issues

| Problem | Cause | Fix |
|---------|-------|-----|
| "Update Patient Record" button greyed out | No patient scanned yet | Tap "Scan Patient Tag" first, hold near Aggregator |
| `TagLostException` during NFC | Devices moved apart | Hold steady, ~3–5 cm, flat surfaces facing each other |
| Voice input not starting | `RECORD_AUDIO` permission not granted | Settings → Apps → NursingDevice → Permissions → Microphone |
| Login fails with "Nurse ID not found" | Nurse not registered yet | Switch to Register mode and register first |
| Backend call hangs for 30–60s | Render.com free tier cold start | Wait and retry; backend wakes up after first request |
