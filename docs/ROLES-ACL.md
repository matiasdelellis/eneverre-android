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

### Current state

- `LoginResponse` carries `role` and `is_admin`; `SecureStore` currently keeps
  only `is_admin`. `POST /api/auth/refresh` now also returns the effective
  `role`/`is_admin`, so the cached role can be updated on renewal.
- `SecureStore.isAdmin()` gates the admin menus (`CamerasActivity` and the
  settings button), but there is no viewer/monitor gating.
- `ViewActivity` always offers the Playback tab; `LiveViewFragment` always
  offers PTZ, privacy and talk when the camera advertises the capability.
- The camera list is already server-filtered; the app caches `Camera` objects
  in `Locations` and the current `Location` intent extras.
- User management (`UsersActivity` / `UserEditActivity`) only understands
  admin vs user.

### Required changes

1. **Persist the real role.**
   - Add `role` to `SecureStore` (alongside `is_admin`), set it from
     `LoginResponse.getRole()` and from every refresh response, and clear it
     on logout.
   - Add helpers such as `canPlayback()`, `canOperate()` and `canMonitor()`
     instead of checking only `isAdmin()`.
   - A role can change server-side mid-session. On a `403` whose detail says
     the capability is missing, refresh the session/role (logout → login is an
     acceptable fallback) and repaint; do not leave the forbidden control
     visible.

2. **Gate the UI.**
   - `viewer`: hide the Playback tab, every playback control, PTZ, privacy and
     talk. Live and events remain.
   - `monitor`: do not enter the camera wall; show a status-only screen or a
     clear "use the API/metrics" message. Monitor users are intended for
     integrations, not the phone UI.
   - `user`/`admin`: current behavior.
   - Admin menus stay `isAdmin()`-only.

3. **Handle ACL-filtered data.**
   - Treat `/api/cameras` as authoritative after every refresh; remove cached
     cameras that disappeared.
   - On `404` from a single-camera endpoint, drop that camera from the current
     `Location`/pager and reload the list instead of retrying forever.
   - When an admin changes a user's ACL, that user's app sees the new list on
     its next refresh; no client-side ACL logic is needed beyond refreshing.

4. **Admin user management.**
   - Add `viewer` and `monitor` to the role selector
     (`UserEditActivity`/`UsersAdapter`); the server rejects unknown roles.
   - Add a camera-ACL editor that calls:
     ```http
     GET /api/users/{username}/cameras
     PUT /api/users/{username}/cameras
     {"restricted":true,"camera_ids":["front","garden"]}
     ```
     An empty list means no cameras; `{"restricted":false}` restores all.
   - Remember: viewer has no playback, monitor has no camera access at all.

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
