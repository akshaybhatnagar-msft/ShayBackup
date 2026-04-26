# Shay Backup

Background photo, video, and downloads backup from Android to Azure Blob Storage.

Like Google Photos / iCloud, but the destination is *your* Azure storage account
and the auth is a **SAS token** you paste into the app — no server, no MSAL.

## What it backs up

- Photos (`MediaStore.Images`)
- Videos (`MediaStore.Video`)
- Downloads (`MediaStore.Downloads`, Android 10+)

Each item lands at `{container}/{category}/{yyyy-MM}/{filename}`.

## Setup

1. **Provision Azure**
   - Create a storage account (LRS / Standard / Hot is fine for media).
   - Create a container, e.g. `phone-backup`.
   - Generate a **container SAS** with permissions: `Read, Write, Create, List`.
     Set an expiry well beyond when you'd want to rotate it.
2. **Install the APK** from Releases.
3. **Open Settings** in the app, paste:
   - `Account URL` — `https://<account>.blob.core.windows.net`
   - `Container` — e.g. `phone-backup`
   - `SAS token` — copy the string starting with `?sv=...&sig=...`
4. Tap **Test connection**. Expect HTTP `201`.
5. Grant media + notification permissions when prompted.
6. Tap **Back up now** for the first run, or leave **Schedule** on for periodic runs.

## How it works

- `MediaScanner` walks `MediaStore` and emits one record per file with a stable dedupe key (`category:_id:date_modified`).
- `BackupEngine` skips anything already in the local history `Set<String>`, opens an `InputStream` via `ContentResolver`, and PUTs it as a single Block Blob. History is persisted in batches of 25.
- `BackupWorker` is a `CoroutineWorker` running as a foreground service while uploading, scheduled by `WorkManager` every N hours (Wi-Fi/charging constraints configurable).
- Auth is a SAS token appended to each blob URL. No keys are ever sent off-device.

## Quality

**Files are uploaded byte-for-byte. No compression, no transcoding, no resizing.**
Each item is read via `ContentResolver.openInputStream(uri)` — the same raw bytes
your camera/download wrote — and `PUT`'d to the blob with the original
`Content-Type`. The blob in Azure is a bit-exact copy of the file on the device.

## Sharing

Long-press a backed-up item in the list to **Copy** or **Share** a download link.
Each link is the blob URL with your container SAS appended, so:

- The link works for anyone you send it to until the SAS expires.
- The link inherits **whatever permissions the SAS has**. If your SAS is
  Read+Write+Create+List, recipients could technically write too. The app shows a
  one-time warning before the first share so this isn't a surprise.
- For read-only sharing, generate a *second* SAS in Azure Portal with only
  `Read` permission and a longer expiry, then paste it into Settings (a separate
  Share-SAS field is on the roadmap).

## Limitations of v1

- Plain `SharedPreferences` for SAS storage. Use a short-lived, scoped SAS, or fork to `EncryptedSharedPreferences`.
- Single Put Blob (good up to ~5 GiB per file with `x-ms-version: 2020-04-08`). Files larger than the network can ship in 10 minutes will fail and retry next run; v0.2 will switch to Put Block + Put Block List for resumable uploads.
- No selected-folder picker yet; only MediaStore-tracked files. SAF support is on the roadmap.
- Dedupe is by `(category, _id, modified_ms)` — moving the file or editing metadata may re-upload it.
- No Azure AD / OAuth. SAS only.

## Build

CI compiles every push and publishes a debug APK as a GitHub Release. The
workflow uses `android-actions/setup-android@v3` plus a `gradle wrapper` step
to generate the wrapper JAR at build time, so nothing is committed under
`gradle/wrapper/`.

Local build:

```bash
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Roadmap

- [ ] Folder picker (SAF tree URIs)
- [ ] Block-blob resumable upload for large files
- [ ] Encrypted-pref storage of SAS
- [ ] Azure AD / MSAL auth
- [ ] Per-album exclude / include
- [ ] Bandwidth cap, off-peak window
- [ ] Restore flow (download an album back to device)
