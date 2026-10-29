# Roles and camera ACL — Android / Android TV work

Server model (eneverre-server `94aa6a4` and later):

| Role | Capabilities |
|---|---|
| `admin` | Everything, including users, cameras, service tokens and ACLs. |
| `user` | Live, playback, PTZ, privacy, talk, events. |
| `viewer` | Live and events; **never playback**, no PTZ/privacy/talk. |
| `monitor` | `/api/status` and `/api/metrics` only; no camera list, live or playback. |

Every role is narrowed by a per-user camera ACL. An unrestricted user sees all
cameras; a restricted user sees only the listed ones. A camera outside the ACL
answers `404` on single-camera endpoints (not `403`), and `/api/cameras` and
`/api/status` are filtered. Admin bypasses the ACL. A service token is linked
to a user and inherits exactly that role and ACL.

## Android phone app (`eneverre-android`)

### Status: implemented in 0.9.0

The client now applies the model above:

- `SecureStore` persists the real `role` next to `is_admin`; login and every
  refresh store it, and `Role`/`SecureStore` expose `canViewCameras()`,
  `canPlayback()`, `canOperate()` and `isMonitor()`.
- `viewer`: live and events only; the Playback tab, PTZ, privacy and talk are
  hidden.
- `monitor`: lands on the status-only `MonitorActivity`; it never fetches the
  camera list.
- A `403` re-reads the role (`ApiClient.refreshRole`) and repaints the screen;
  a `403` is never treated as a dead session.
- The camera list is authoritative after every refresh; a `404` from a
  single-camera endpoint confirms the camera against a fresh list and closes
  the view if it is gone.
- Admin user management knows all four roles and edits the per-user camera ACL
  through `GET/PUT /api/users/{username}/cameras` ("all cameras" sends
  `{"restricted":false}`).

The Android TV notes below are unchanged.

## Android TV app (`eneverre-tv`)

- The TV app currently only plays live; a `viewer` user is fine, a `user` is
  fine, and an `admin` is clamped to `user` for device sessions.
- A `monitor` device has no camera list/live endpoints. If the paired user is
  monitor, show a status-only message instead of an empty grid.
- The camera list from `/api/cameras` is already ACL-filtered; handle an empty
  list per location without treating it as an error.
- If the TV app later adds playback, gate it exactly like the phone
  (`viewer`/`monitor` never playback; `user`/`admin` do).
- Device login returns the effective role in the session; no separate ACL
  logic is needed because the server filters every camera response.
