# CAD (NursingDevice) — Credentials, Encrypted Storage & Cert-Based NFC

This document covers the changes that (1) store the nurse's server-issued
credential in an encrypted database, (2) move all other data off-disk, and
(3) use the credential for certificate-based mutual authentication over NFC.

> **Status:** implemented, **not yet compiled** in this environment (no Android
> SDK was available). Build in Android Studio and address any first-sync issues
> (notably SQLCipher artifact resolution). See the error reference in §6.

Related: the backend that issues credentials is `Nursing-Backend/`
(`/api/nurses/register` returns a `credentials` object). The Aggregator has a
mirror of this document.

---

## 1. What changed (files)

| File | Change |
|------|--------|
| `app/build.gradle.kts` | + `net.zetetic:android-database-sqlcipher:4.5.4`, `androidx.sqlite:sqlite-ktx:2.4.0` |
| `PinCrypto.kt` | **NEW** — PBKDF2(PIN + nurseId, salt) → 256-bit key; salt in SharedPreferences |
| `NursingDeviceDatabase.kt` | **REWRITTEN** — SQLCipher DB holding **only** a `credentials` table |
| `CredentialStore.kt` | **NEW** — `Credentials` DTO, in-memory `CredentialHolder`, `UnlockResult`, `unlock()`, `saveFromServer()` |
| `NursePatientManager.kt` | **REWRITTEN** — nurse profile → SharedPreferences, patient context → in-memory `SessionCache`, records → `cacheDir` files (same method signatures) |
| `NurseRepository.kt` | parses `credentials` from register; returns `NurseRegistration(nurse, credentials)` |
| `AuthActivity.kt` + `res/layout/activity_auth.xml` | PIN field; register saves credentials under the PIN; login unlocks the store |
| `CryptoUtils.kt` | `CERT_AUTH_ENABLED`, `getSessionPrivateKey()`, cert getters, `verifyPeerCert()` (+ `PeerCertException`) |
| `NfcAuth.kt` | **NEW** — reader-side certificate exchange |
| `MyHostApduService.kt`, `ReaderActivity.kt`, `FetchRecordActivity.kt` | cert-exchange step wired into the NFC handshake |
| `app/src/main/assets/tca-certificate.pem` | replaced with the current CA certificate |

---

## 2. Storage model — credentials only on disk

Per the design, the CAD keeps **only the credential** on disk, encrypted:

```
Encrypted Room (SQLCipher)   nursing_device_creds.db
  credentials(ownerId, role, privateKeyB64, publicKeyB64, certPem, caCertPem, ...)

NOT on disk (as before this change):
  scanned patient  -> SessionCache (RAM only)
  fetched/session   -> app cacheDir files (OS-clearable)
  nurse profile     -> SharedPreferences (identity, not patient data)
```

The DB passphrase = `PBKDF2WithHmacSHA256(PIN + ":" + nurseId, salt, 100k, 256-bit)`.
The PIN is never stored; the salt (not secret) is in SharedPreferences.

---

## 3. Credential lifecycle

```
REGISTER (AuthActivity)
  POST /api/nurses/register -> { nurse, credentials:{ privateKey, publicKey, certificate, caCertificate } }
  CredentialStore.saveFromServer(pin, nurseId, "nurse", credentials)
     -> derive key from PIN -> open encrypted DB -> store credential -> hold in memory

LOGIN (AuthActivity)
  CredentialStore.unlock(pin, nurseId) -> UnlockResult
     Success       : credential loaded into CredentialHolder (used by NFC)
     NoCredential  : PIN correct, but nothing stored yet (register / rotate)
     Failed(reason): usually a wrong PIN — reason is shown to the nurse
```

`CredentialHolder.current` lives in memory only. After the app process is killed
the nurse must log in again (PIN) before NFC will work.

---

## 4. NFC handshake (certificate-based)

`CryptoUtils.CERT_AUTH_ENABLED = true`. The tap:

```
SELECT
AUTH_CRT   reader sends its cert; card verifies it vs the CA, replies with its cert;
           reader verifies the card's cert vs the CA        (NfcAuth.exchangeCerts)
AUTH_KEY   reader encrypts a fresh AES session key to the peer's cert public key
AUTH_SIG   reader signs with its own private key; card verifies + decrypts
DATA       AES/XOR-encrypted transfer (unchanged)
```

This implements the "Ideal NFC Handshake" described in the repo's `AUTH.md`
(cert exchange + CA-signature verification + expiry check), with one difference
from that older sketch: the keypair is **generated on the server** (not on-device)
and the private key is protected by the **PIN** (no Android Keystore/StrongBox on
the target hardware). **Revocation is not checked during the offline tap** — only
the CA signature and expiry are (revocation is an online check via
`GET /api/credentials/verify/:ownerId`).

**Operational requirement:** both apps must be **open and logged in (PIN)** during a
tap — the credential lives in memory. To revert to the legacy hardcoded-key
handshake, set `CERT_AUTH_ENABLED = false` in **both** apps and rebuild.

---

## 5. Build & run

```bash
cd NursingDevice
./gradlew assembleDebug     # requires Android SDK + JDK (Android Studio)
```
The new SQLCipher dependency ships the native libs; `SQLiteDatabase.loadLibs(context)`
is called inside `NursingDeviceDatabase`. If Gradle cannot resolve the artifact,
confirm `mavenCentral()` is in the repositories block.

---

## 6. Error reference (exact message → cause → fix)

| Message (Toast / NFC status / log) | Cause | Fix |
|---|---|---|
| `Login failed: incorrect PIN (could not decrypt the credential store)` | Wrong PIN for this device | Enter the PIN set at registration |
| `PIN OK, but no credentials on this device yet…` | Store opened, but empty | Register, or call `/api/credentials/rotate` and re-provision |
| `Registered as … (credential save failed)` | Server returned creds but the encrypted write failed | Check logcat `CredentialStore`; verify SQLCipher loaded |
| `No credential on this device — log in with your PIN first…` (NFC) | `CredentialHolder` empty (not logged in / process was killed) | Log in again before tapping |
| `Peer cert rejected: peer certificate is NOT signed by the trusted CA…` | The other device uses a different CA | Re-issue both devices from the same backend/CA; re-bundle the CA cert |
| `Peer cert rejected: peer certificate expired on …` | Peer credential expired (>1 yr) | Rotate the peer's credential (`/api/credentials/rotate`) |
| `Peer certificate rejected: peer certificate not valid until … (check device clock)` | Device clock skew | Fix the device date/time |
| `Certificate exchange rejected by peer (status 6A88)…` | Peer couldn't complete cert exchange (often not logged in) | Ensure the peer app is open + logged in |
| `Authentication rejected by peer — signature/credential mismatch…` | Keys/certs don't correspond | Confirm both devices registered under the same CA and logged in |

`6A88` = our `FILE_NOT_READY_SW`; `0000` = `UNKNOWN_CMD_SW` (Aggregator side).

---

## 7. Known limitations / follow-ups
- **Not compiled here** — build and fix any import/version issues first.
- **NFC needs both apps open + unlocked** (credential in memory). Optional future work: back the key with Android Keystore so HCE works app-closed.
- No CAD `MainActivity` "locked → go to login" guard yet (Aggregator has one). Consider adding.
- Changing the PIN re-derives a different key and cannot open the existing store (no rekey implemented) — prototype limitation.
- After hardware verification, delete the hardcoded `MY_PRIVATE_KEY_STR` / `OTHER_PUBLIC_KEY_STR` and the legacy `else` branches.
