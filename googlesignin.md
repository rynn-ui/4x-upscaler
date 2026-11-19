# 🔐 Google Sign-In — 4X Upscaler Studio

> **File:** `googlesignin.md`
> **Repository:** [rynn-ui/upscaler-studio](https://github.com/rynn-ui/upscaler-studio)
> **Purpose:** Step-by-step setup guide for adding Google Login (OAuth 2.0 / ID tokens) to the Android Upscaler Studio app and its Colab FastAPI backend.

---

## 1. How it works

The app already authenticates every backend request with a bearer token (`Authorization: Bearer <token>`), sent by the OkHttp interceptor on REST calls and as a WebSocket header/query param. Google Sign-In simply replaces (or supplements) that legacy per-session token with a **signed Google ID token (JWT)**:

1. **Android** signs the user in with Google → receives an ID token.
2. **Android** sends that ID token as the `Authorization: Bearer` credential on every backend call.
3. **Backend (FastAPI on Colab)** verifies the ID token's signature, expiry, issuer, and audience using Google's `google-auth` library.
4. On success, the session is bound to the user's Google account (`sub` / `email` / `name` / `picture`).

Legacy session tokens still work as a fallback, so existing QR/deep-link pairing is not broken.

```
Android app      Google Sign-In       Backend (FastAPI / Colab)
-------      ----------------       ---------------------------
         --(1) sign in intent-->
                                          (Google issues signed ID token)
         <--(2) ID token (JWT)--------
         --(3) Bearer ID token------>
                                          verify signature/audience/expiry
                                          bind session to Google account
         <--(4) 200 OK / 401----------
```

---

## 2. Prerequisites (Google Cloud Console)

1. Go to [console.cloud.google.com](https://console.cloud.google.com/).
2. Create a project (or select an existing one).
3. **Enable** the **Google Identity Services API** (and Google Sign-In API).
4. **Credentials → Create credentials → OAuth 2.0 Client ID → Web application**:
   - Note the **Web Client ID** (ends in `.apps.googleusercontent.com`). This is the *audience* both sides must agree on.
5. **Credentials → Create credentials → OAuth 2.0 Client ID → Android**:
   - Package name: `com.ryn.studio`
   - SHA-1 certificate fingerprint of your signing certificate (debug keystore for testing, release keystore for Play Store builds).
6. Download the real `google-services.json` from Firebase (Project: `x-upscaler`) and place it at `android/app/google-services.json`.

---

## 3. Firebase Authentication Integration

Google Sign-In is now unified with **Firebase Authentication** (`com.google.firebase:firebase-auth`):
- When a user selects their Google account, the Google ID token authenticates with Firebase (`signInWithCredential`).
- Firebase provisions a permanent, globally unique **Firebase User ID (`UID`)**.
- User sessions are automatically tracked and viewable in the **Firebase Console ➔ Authentication ➔ Users** dashboard.
- Users can view and copy their unique Firebase UID directly from the in-app User Account dialog.

---

## 4. Backend setup (`backend/`)

### 3.1 Install dependency

Add to `backend/requirements.txt`:

```text
google-auth>=2.23.0
```

### 3.2 Set the audience env var

In the Colab notebook cell (or runtime environment), set `GOOGLE_CLIENT_ID` to your Web Client ID **before** the backend starts:

```python
import os
os.environ["GOOGLE_CLIENT_ID"] = "YOUR_GOOGLE_WEB_CLIENT_ID.apps.googleusercontent.com"
```

This must match the `google_web_client_id` string resource in the Android app.

### 3.3 Endpoints affected

| Endpoint | Auth behavior |
|----------|---------------|
| `GET /api/v1/health` | `Authorization: Bearer <token>` (session token **or** Google ID token) |
| `GET /api/v1/capabilities` | Same |
| `POST /api/v1/jobs/*` | Same |
| `GET /api/v1/auth/me` | **New.** Returns the authenticated user's profile. Works with both session and Google tokens. |
| `WS /api/v1/jobs/{job_id}/stream` | Accepts token via `Authorization` header, `X-Upscaler-Token` header, or `?token=` query param |

### 3.4 What the backend verifies

For a Google ID token it checks:
- Cryptographic signature (Google's public certs).
- Expiry (`exp`) and not-before (`iat`).
- Issuer (`iss` is `accounts.google.com` or `https://accounts.google.com`).
- **Audience (`aud`) == `GOOGLE_CLIENT_ID`** — this is the critical anti-confused-deputy check.

On success it returns:
```json
{
  "provider": "google",
  "google_id": "<Google sub>",
  "email": "user@gmail.com",
  "name": "User Name",
  "picture": "https://...",
  "email_verified": true
}
```

---

## 4. Android setup (`android/`)

### 4.1 Dependencies (`gradle/libs.versions.toml` + `build.gradle.kts`)

Added to version catalog:

```toml
playServicesAuth = "21.2.0"
...
play-services-auth = { group = "com.google.android.gms", name = "play-services-auth", version.ref = "playServicesAuth" }
```

Applied plugin in `android/build.gradle.kts`:

```kotlin
id("com.google.gms.google-services") version "4.4.2" apply false
```

And in `android/app/build.gradle.kts`:

```kotlin
plugins {
    id("com.google.gms.google-services")
}
...
implementation(libs.play.services.auth)
```

### 4.2 Credentials file

Replace `android/app/google-services.json` (currently a placeholder) with the real file downloaded from Google Cloud Console. Fill in the real `client_id`, `api_key`, and `certificate_hash`.

### 4.3 String resources (`res/values/strings.xml`)

Replace the placeholder with your real Web Client ID:

```xml
<string name="google_web_client_id">YOUR_GOOGLE_WEB_CLIENT_ID.apps.googleusercontent.com</string>
```

This **must** match the backend `GOOGLE_CLIENT_ID` env var.

### 4.4 New files

| File | Purpose |
|------|---------|
| `data/auth/GoogleAuthManager.kt` | Builds `GoogleSignInClient` (requests ID token for the Web Client ID), provides sign-in intent, parses the activity result, persists ID token + profile to `SharedPreferences`. |
| `googlesignin.md` | This document. |

### 4.5 Modified files

| File | Change |
|------|--------|
| `ui/home/HomeScreen.kt` | Added "Sign in with Google" button + sign-out in the connect dialog. On success, stores the ID token and pre-fills the session-token field; tapping **Connect** binds the Google account to the backend session. |
| `backend/server.py` | Added Google ID-token verification, `require_auth` accepts either token type, added `GET /api/v1/auth/me`. |
| `backend/requirements.txt` | Added `google-auth`. |

---

## 5. End-to-end flow

1. User connects to the Colab backend via the existing QR/deep-link flow → a legacy session token is established.
2. User taps **"Sign in with Google"** → Google account picker → app receives a signed ID token.
3. User taps **Connect** → `connectAndVerify(url, id_token)` → backend verifies the Google ID token → session bound to that Google account.
4. All subsequent REST/WebSocket calls send `Authorization: Bearer <id_token>`.
5. `GET /api/v1/auth/me` returns the Google profile (`provider: "google"`); legacy session tokens return `provider: "session"`.

---

## 6. Token lifetime & caveats

- **Google ID tokens expire after ~1 hour.** When one expires, backend requests return `401` and the user must re-sign in. For an ephemeral Colab session backend this is acceptable.
- Add Google refresh-token handling if you need longer-lived sessions across Colab runtime restarts.
- The backend instance ID (`/api/v1/health`) still detects Colab session rotation/restarts; a new Colab runtime generates a new session token, but the Google ID token is independent of that.

---

## 7. How to Check Which Users Are Signed In

You can monitor and manage your users through two places:

### 1. Firebase Console (User Dashboard)
- Open: [Firebase Console ➔ x-upscaler ➔ Authentication](https://console.firebase.google.com/project/x-upscaler/authentication/users)
- The **Users** tab lists every person who has signed into your app:
  - **User UID:** Permanent unique identifier (e.g., `8dE2q9L...`).
  - **Identifier / Email:** The user's Google account email.
  - **Created:** The exact timestamp the user first opened and signed into the app.
  - **Last Signed In:** The timestamp when they last launched and authenticated.
  - **Actions:** You can disable accounts, delete accounts, or reset user data directly.

### 2. Colab FastAPI Backend Logs
- Whenever a user interacts with the Colab backend, the terminal logs:
  `INFO: User <email> (ID: <google_id>) connected to studio`
- You can query `GET /api/v1/auth/me` on your active Colab tunnel URL to see the current active user profile.

---

## 8. Quick checklist

- [ ] Google Cloud Console project created; Google Identity Services API enabled.
- [ ] Web OAuth client ID created (audience).
- [ ] Android OAuth client ID created with correct SHA-1 fingerprint.
- [ ] `GOOGLE_CLIENT_ID` env var set in Colab before backend starts.
- [ ] `android/app/google-services.json` replaced with real credentials.
- [ ] `res/values/strings.xml` `google_web_client_id` set to the real Web Client ID.
- [ ] Gradle Sync performed in Android Studio.
- [ ] `GET /api/v1/auth/me` returns `provider: "google"` after signing in.

*Last Updated: November 2026 • Maintained for [rynn-ui/upscaler-studio](https://github.com/rynn-ui/upscaler-studio)*

