# RTSP path tokens — Android and Android TV work

Server-side change: `eneverre-server` commit `7161ab5` replaced the global
rotating RTSP username/password with a per-camera signed token in the URL
path.

```
rtsp://<host>:<port>/<camera-id>/<exp>.<sig>
```

- No `user:pass@` in the URL, no RTSP `Authorization` header.
- The token is HMAC-SHA256, scoped to one camera, valid for **6 hours**.
- It is checked on RTSP `DESCRIBE`/`SETUP`; the RTP stream is unchanged.
- An already-playing session survives token expiry. A **new connection**
  (reconnect, app resume, another camera) needs a fresh URL from
  `GET /api/cameras`.
- A missing, expired, or wrong-camera token gets `401 Unauthorized`.
- `live_mse`, playback and talk are unchanged. This document is only about
  the live RTSP URL.

This is a breaking server change: URLs built as
`rtsp://user:pass@host:554/<id>` (or with Basic credentials) no longer work.

---

## Android phone app (`eneverre-android`)

### Current code

- `api/model/Camera.java` exposes the server's `rtsp` field through
  `getRtsp()`.
- `LiveViewFragment.startLive()` does `Uri.parse(currentCamera.getRtsp())`
  and hands it to `RtspPlayerView.playUri()`.
- `player/RtspPlayerView.playUri()` still splits an optional `userInfo` off
  the URL and passes username/password to the library. That branch is now
  dead code: the new URL never has userinfo.
- `LiveViewFragment` already has a one-shot recovery:
  `livePlayerListener.onError()` calls `refreshRtspAndRetry()` once, which
  re-fetches `GET /api/cameras`, finds the same camera, updates
  `currentCamera` and retries. It resets the one-shot flag on `onPlaying()`.
- `MosaicAdapter.CellHolder.start()` and `retry()` reuse
  `camera.getRtsp()` directly. A cell error only shows the retry icon; the
  full view is what currently re-fetches a URL.
- The camera list is fetched at cold start (`SplashActivity`), after login
  (`LoginActivity`) and by `CamerasActivity.refreshCameras()` (pull to
  refresh). There is no age check before opening a live view.

### Required changes

1. **Make the URL opaque.**
   - Remove the userinfo split in `RtspPlayerView.playUri()`; keep
     `username = null`, `password = null` and pass the URL exactly as the
     API returned it.
   - Do not add an `Authorization` header to RTSP requests. The path token is
     the credential.
   - Do not rebuild the URL, append a query, or percent-encode the token.
     `Uri.parse()` already preserves the two path segments.

2. **Know when a URL is close to expiry.**
   - Add a small helper (for example in `api/model/Camera` or a new
     `player/RtspUrl` utility) that returns the token expiry:
     ```java
     // rtsp://host:port/<camera-id>/<exp>.<sig>
     static long rtspTokenExpiresAt(String url) {
         if (url == null || url.isEmpty()) return 0;
         String last = Uri.parse(url).getLastPathSegment();
         if (last == null) return 0;
         int dot = last.lastIndexOf('.');
         if (dot <= 0 || dot == last.length() - 1) return 0;
         try {
             return Long.parseLong(last.substring(0, dot));
         } catch (NumberFormatException e) {
             return 0;
         }
     }
     ```
   - Use a safety margin (30 minutes is enough) rather than waiting for the
     exact second.
   - If the device clock is wrong, the margin can cause repeated refreshes.
     Cap the proactive refresh to one per view/session and fall back to the
     existing error-driven retry.

3. **Refresh before starting a stale URL.**
   - Before `playUri()`, if the URL is empty, malformed, or expires within
     30 minutes, re-fetch `/api/cameras` first and replace `currentCamera`.
   - Also refresh when returning to a live screen after the app was in the
     background for a long time (e.g. camera list older than 5 hours). A
     6-hour token must not be reused after a long pause.
   - Keep the existing one-shot error refresh; it is the correct last line of
     defense when the server rejects a token that looked fresh.

4. **Fix the mosaic.**
   - `MosaicAdapter` cells must not keep retrying a stale URL.
   - Add one refresh path owned by `MosaicFragment`/`MosaicActivity` (or a
     shared helper): on a cell's first error, refresh the camera list once,
     update every cell's `Camera`, and restart the failed/visible cells.
   - Do not let four cells issue four `/api/cameras` requests at once.
     Collapse concurrent refreshes into one request.
   - On a manual cell retry, use the same refresh path if the token is near
     expiry or the previous attempt was rejected.

5. **Update comments and logs.**
   - Replace "rotating MediaMTX credentials" comments with "per-camera signed
     path token".
   - Never log the full RTSP URL: the last path segment is a bearer
     credential. Log the camera id (or redact the last segment) instead.
   - If a library error string contains the URL, sanitize it before logging.

### Acceptance criteria

- `./gradlew assembleDebug` succeeds.
- A camera plays with the exact `rtsp` string from `GET /api/cameras`; the
  request has no userinfo and no RTSP auth header.
- A URL whose camera id is changed to another camera is rejected with `401`;
  the view refreshes once and retries with the correct URL.
- Leaving the app open past the token expiry and reconnecting triggers a
  camera-list refresh, not a permanent error.
- The mosaic recovers after a token expiry with one refresh (not one per
  cell).
- No RTSP token appears in `logcat`.

---

## Android TV app (`eneverre-tv`)

The TV client is a separate repository/codebase. It must make the same
credential change, plus a field-name fix that is a prerequisite for live
playback with the current server.

### Current code

- `api/model/Camera.java` maps `@SerializedName("live")` and exposes
  `getLive()`. The current server returns `rtsp` (and `live_mse`), not
  `live`, so this must become `rtsp` / `getRtsp()`.
- `LiveActivity.playVideo()` reads `camera.getLive()`, splits `userInfo`,
  and calls `RtspSurfaceView.init(uri, username, password, ...)`.
- `LocationGridActivity.createTile()` repeats the same split for each tile.
- `LaunchActivity` fetches `/api/cameras` once with the stored Bearer token
  and passes the list through `Intent` extras. There is no camera-list
  refresh, and the device token has no refresh flow.
- The reconnect button calls `playVideo(cameraList.get(currentIndex))` with
  the same stale `Camera` object.

### Required changes

1. **Use the right field.**
   - Change the Gson mapping from `live` to `rtsp`, add `getRtsp()`, and use
     it in `LiveActivity` and `LocationGridActivity`.
   - Keep `live_mse` unused: the TV app plays RTSP, not MSE.

2. **Treat the URL as opaque.**
   - Remove the userinfo split in both activities; call
     `init(uri, null, null, "EneverreTV", null)`.
   - Do not add HTTP/RTSPS credentials or headers.

3. **Refresh the camera list on expiry/reconnect.**
   - Add the same expiry helper as the phone app.
   - On the reconnect button, if the token is near expiry or the last attempt
     was `401`, re-fetch `/api/cameras` before `playVideo()`.
   - On `onRtspStatusFailedUnauthorized()`, refresh once, replace the current
     camera by id, and retry; if the refresh fails, show the existing error
     UI.
   - For the grid, refresh once for the whole grid and rebuild the tiles with
     fresh URLs; do not refresh independently per tile.
   - If the Bearer access token itself is expired, the existing login flow
     applies; that is separate from the RTSP token.

4. **Logging.**
   - `BuildConfig.DEBUG` enables the library's debug logging. Make sure the
     RTSP token is not included in app logs; redact the last path segment if
     the library prints the URL.

### Acceptance criteria

- `./gradlew assembleDebug` succeeds.
- A TV live stream plays from the `rtsp` field (no `live` field needed).
- Reconnect after token expiry re-fetches cameras and plays.
- The 2x2 grid recovers with one refresh, not four.
- No RTSP token in logcat.

---

## Rollout note

Deploy the server first, then these client builds. Old clients that still
build `rtsp://user:pass@...` or read the old `live` field will fail on live
streaming after the server update; `live_mse` (web) and playback are not
affected by this change.
