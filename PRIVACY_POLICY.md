# Privacy Policy — MaxTV Player

**Effective date:** 26 September 2026
**Developer:** Maslarski (contact: maslarskizharko@yahoo.com)
**Application:** MaxTV Player (Android package `com.maslarski.iptv`)

MaxTV Player is a general-purpose media player for Android TV, Fire TV and Android devices. It plays
IPTV playlists (M3U/M3U8, Xtream Codes) and electronic programme guides (XMLTV) **that you supply
yourself**. The app does not include, sell, host, or recommend any television channels, video content,
or playlist sources. You are solely responsible for the legality of the content you choose to load.

This policy explains what data the app processes, why, where it is stored, and the choices you have.

---

## 1. Summary

| Data | Purpose | Where it goes | Retention |
|---|---|---|---|
| Pseudonymous device identifier (derived from Android ID) | Free-trial and licence status tracking | Google Firebase Firestore (Google Cloud, EU/US regions) | While the device record exists; deletable on request |
| Device model, app version, first-seen / last-seen timestamps, subscription status, plan, expiry | Licence validation and support | Firebase Firestore | Same as above |
| Crash reports and performance traces (stack traces, device model, OS version, app version, anonymised session) | App stability and performance diagnostics | Sentry (Functional Software, Inc.), EU data centre (`ingest.de.sentry.io`) | 90 days (Sentry default) |
| Playlist URLs, Xtream credentials, EPG URLs, favourites, watch progress, reminders, parental PIN (hashed), settings | Core app functionality | **Only on your device** (encrypted-at-rest Android storage) | Until you delete the playlist or uninstall |
| Movie / series titles you browse | Fetching artwork and synopses | The Movie Database (TMDB) API | Not stored by us; TMDB terms apply |
| Purchase token (if you buy via Google Play) | Verifying the lifetime licence | Google Play Billing | Managed by Google Play |

We do **not** collect names, e-mail addresses, phone numbers, contacts, precise or coarse location,
advertising identifiers, photos, microphone or camera data. The app contains **no advertising SDKs**
and **no analytics SDK** (Firebase Analytics is not included in the build).

---

## 2. Device identifier and Firebase Firestore (trial & licence tracking)

To offer a 7-day free trial that cannot be reset by clearing app data or changing the device clock, the
app registers each installation in a Firebase Firestore database:

* **Identifier.** A stable, pseudonymous identifier is derived from the device's `ANDROID_ID` via a
  one-way hash and formatted as a MAC-style string (e.g. `7A:3F:0C:91:B2:E4`). It is **not** the
  hardware MAC address of any network interface, it cannot be reversed to the Android ID, and it
  resets when the device is factory-reset. This value is displayed to you in *Settings → Account
  Details* so you can quote it when requesting activation or deletion.
* **Fields stored** in `devices/{deviceId}`: `deviceId`, `createdAt` (server timestamp),
  `lastSeenAt` (server timestamp), `appVersion`, `model` (manufacturer + model),
  `subscriptionStatus` (`trial`, `active`, `expired`), `plan`, `expiresAt`, `activatedAt`, and optional
  `remotePlaylistUrl`, `remotePlaylistName` and `remoteEpgUrl` fields that an administrator may set,
  at the request of the device owner, to pre-configure that owner's own playlist on the device.
* **Server-side timestamps** are used so that the trial length is measured by Google's servers, not
  by the device clock.
* **Security.** Data is transmitted over TLS. Firestore security rules restrict each device to reading
  and updating only its own document. Firebase App Check may be used to reject requests that do not
  originate from the genuine app. Firestore data is stored by Google LLC under the
  [Firebase Data Processing and Security Terms](https://firebase.google.com/terms/data-processing-terms).
* **Offline.** The last known licence status is cached locally so the app works without a network
  connection; it re-synchronises when connectivity returns.

The legal basis for this processing (GDPR Art. 6(1)(b) and (f)) is performance of the licence
agreement and our legitimate interest in preventing trial abuse.

## 3. Crash and performance reporting (Sentry)

The app uses the Sentry Android SDK to detect crashes and slow operations so we can fix them.

* **What is sent:** exception type and stack trace, thread state, app version and build, Android
  version, device manufacturer/model, memory and storage headroom, screen orientation, locale,
  network type (Wi-Fi / cellular), and breadcrumbs describing recent app screens. A random session
  identifier groups events from the same app run.
* **Anonymisation:** Sentry's *send default PII* option is **disabled**. No user name, e-mail, or
  IP address is attached to events — the client IP is discarded by Sentry at ingestion rather than
  stored. Screenshots and view hierarchies are **not** captured. Playlist URLs and Xtream
  credentials are never included in error messages; network spans record only the host name of
  non-streaming API calls, and video segment requests are excluded from tracing entirely.
* **Sampling:** performance traces are recorded for 20 % of sessions in production builds.
* **Location & retention:** events are processed in Sentry's EU region (Frankfurt) and deleted after
  90 days. Sentry acts as our processor under its
  [Data Processing Addendum](https://sentry.io/legal/dpa/).

Firebase Crashlytics is **not** used.

## 4. Data stored only on your device

The following never leaves your device unless you export or back it up yourself:

* Playlist sources: M3U/M3U8 URLs, Xtream Codes server address, username and password, EPG URLs.
* Parsed channel, film, series and programme-guide data (Room database).
* Favourites, hidden/reordered categories and channels, watch progress and "continue watching".
* Programme reminders and auto-switch alarms.
* Parental-control PIN — stored only as a SHA-256 hash, never in plain text.
* Preferences: language, refresh interval, last-watched channel, playback settings.
* Cached artwork (disk cache, max 256 MB).

Android's automatic backup (`allowBackup`) may include these settings in your Google account backup
if you have device backup enabled; this is controlled by your Android system settings.

## 5. Third-party services you connect to

* **Your IPTV provider.** When you add a playlist or Xtream account, the app connects directly from
  your device to the server you specify to download the playlist, EPG and media streams. That
  provider sees your IP address and credentials; its own privacy terms apply. We have no relationship
  with, and receive no data from, any IPTV provider. Cleartext (HTTP) connections are permitted
  because many private servers do not offer TLS — use HTTPS URLs where available.
* **The Movie Database (TMDB).** For films and series that lack artwork or a synopsis, the app queries
  the TMDB API with the title (and year, if known) to fetch posters and descriptions. Only the title
  is sent; no device or account data. TMDB's [privacy policy](https://www.themoviedb.org/privacy-policy)
  applies. This product uses the TMDB API but is not endorsed or certified by TMDB.
* **Google Play Billing.** If you purchase the lifetime licence, the transaction is handled entirely by
  Google Play. We receive a purchase token and product ID to unlock the licence; we never see your
  payment details.

## 6. Permissions explained

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Stream media, download playlists/EPG, sync licence status; detect offline mode |
| `WAKE_LOCK` | Keep the screen on during playback and finish background playlist refreshes |
| `POST_NOTIFICATIONS` | Show the programme reminder you scheduled |
| `SCHEDULE_EXACT_ALARM` | Fire a programme reminder or channel auto-switch at the exact start time you chose (Android 12+; falls back to an inexact alarm if you decline) |

The app requests no location, storage, contacts, microphone, camera or background-location
permissions and runs no foreground services.

## 7. Children

MaxTV Player is a general-audience utility and is not directed at children under 13. It contains a
parental-control PIN so that adults can restrict access to categories they choose. We do not knowingly
collect personal data from children.

## 8. Your rights and choices

* **View** your device identifier and licence status at any time in *Settings → Account Details*.
* **Delete** your Firestore device record: e-mail the address below quoting the device identifier and
  we will delete it within 30 days. Note that deletion ends any active licence tied to that device.
* **Stop crash reporting:** uninstalling the app stops all reporting; crash data already collected is
  removed automatically after 90 days.
* **Delete local data:** remove a playlist in *Settings → Playlists*, or uninstall the app / clear
  storage in Android settings.
* EU/EEA and UK residents may exercise rights of access, rectification, erasure, restriction,
  portability and objection under the GDPR by contacting us, and may lodge a complaint with their
  supervisory authority.

## 9. Data sharing and sale

We do not sell, rent, or share personal data with third parties for their own purposes. Data is
processed only by the sub-processors listed above (Google Firebase, Sentry, TMDB, Google Play) acting
on our instructions or under their published terms, and disclosed otherwise only where required by law.

## 10. Security

Data in transit to Firebase, Sentry, TMDB and Google Play is encrypted with TLS. Local data is stored in
the app's private, sandboxed storage. Firestore access is limited by security rules to each device's own
record. No system is perfectly secure; if we become aware of a breach affecting your data we will notify
you as required by law.

## 11. Changes to this policy

We may update this policy when the app's features change. The effective date at the top will be
revised and material changes will be announced in the app's release notes. The current version is
always available at the URL published on the Google Play store listing.

## 12. Contact

Maslarski — maslarskizharko@yahoo.com
