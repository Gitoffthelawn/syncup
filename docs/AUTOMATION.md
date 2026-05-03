# Automating SyncUp on Android

SyncUp on Android exposes a small broadcast-intent surface so automation
tools (Tasker, MacroDroid, Llama, etc.) can start the daemon, stop it, or
force a folder rescan.
This is Android-only.

---

## Enable external control first

The broadcast surface is **off by default**. Open SyncUp →
**Settings → Automation → Allow external control**. Read the dialog,
confirm. Until that toggle is on, every broadcast is logged and dropped:

```
W AppConfigReceiver: rejected com.siddarthkay.syncup.action.START: external control disabled in settings
```

Flip the toggle off again at any time to revoke the surface.

---

## Actions

### Start the daemon
```
am broadcast -n com.siddarthkay.syncup/.AppConfigReceiver \
  -a com.siddarthkay.syncup.action.START
```
Equivalent of opening the app, the foreground service starts and the
daemon comes up. No-op if already running.

### Stop the daemon
```
am broadcast -n com.siddarthkay.syncup/.AppConfigReceiver \
  -a com.siddarthkay.syncup.action.STOP
```
Stops the foreground service. The persistent notification disappears and
sync halts until the next start.

### Rescan all folders
```
am broadcast -n com.siddarthkay.syncup/.AppConfigReceiver \
  -a com.siddarthkay.syncup.action.RESCAN
```
POSTs `/rest/db/scan` to the local daemon. Daemon must be running.

### Rescan a specific folder
```
am broadcast -n com.siddarthkay.syncup/.AppConfigReceiver \
  -a com.siddarthkay.syncup.action.RESCAN \
  --es folder "<folder-id>"
```
Same as above but limited to one folder. The `folder` extra is the folder
ID (not the label), get it from the folder detail screen. An unknown
folder ID returns HTTP 500 from the daemon and is logged as
`rescan returned 500` in `AppConfigReceiver` logcat.

---

## Tasker recipes

In Tasker, **Send Intent** action:

| Field | Value |
|---|---|
| Action | `com.siddarthkay.syncup.action.START` |
| Cat | None |
| Target | Broadcast Receiver |

Sample triggers:

- **Start when on home WiFi**: WiFi Connected (SSID = home) → Send Intent
  START.
- **Stop when battery < 20%**: Battery Level < 20 → Send Intent STOP.
- **Force rescan when entering office**: Enter Geofence → Send Intent
  RESCAN with `folder` extra set to your work-vault folder ID.

For the RESCAN action with a folder extra in Tasker, set:
- **Extra**: `folder:work-vault`

(Replace `work-vault` with the actual folder ID, visible in the SyncUp
folder detail screen.)

---

## ADB testing

Useful for verifying the surface works without installing Tasker:

```
adb shell am broadcast -n com.siddarthkay.syncup/.AppConfigReceiver -a com.siddarthkay.syncup.action.START
adb shell am broadcast -n com.siddarthkay.syncup/.AppConfigReceiver -a com.siddarthkay.syncup.action.RESCAN
adb shell am broadcast -n com.siddarthkay.syncup/.AppConfigReceiver -a com.siddarthkay.syncup.action.RESCAN --es folder "<folder-id>"
adb shell am broadcast -n com.siddarthkay.syncup/.AppConfigReceiver -a com.siddarthkay.syncup.action.STOP
```

The receiver logs to logcat under tag `AppConfigReceiver`. Tail with:

```
adb logcat -s AppConfigReceiver
```

---

## Security considerations

The threat model: this surface lets any other app on the device drive
SyncUp's daemon lifecycle. Without thinking about it, that's an
unwarranted ambient-authority grant.

The mitigations layered on:

1. **Default-OFF opt-in.** Settings → Automation → Allow external control
   has to be flipped on by the user. Until it is, every broadcast is
   dropped with a `rejected` warning in logcat.
2. **Caller logging.** When a broadcast is accepted, the receiver logs
   the action and the calling package (or `unknown` if Android does not
   surface it) at INFO level. `adb logcat -s AppConfigReceiver` audits
   the trail.
3. **Action surface is intentionally narrow.** The receiver only handles
   start, stop, and rescan. There is no broadcast surface for adding
   folders, accepting devices, changing config, or anything that
   modifies cluster state. Anything destructive still requires the UI.
4. **Foreground-service-only side effects.** START/STOP route through
   `SyncthingService` which runs as a `dataSync` foreground service —
   the system surfaces the persistent notification while the daemon is
   alive, so a misbehaving automation that flaps the daemon is visible.

What this is *not*:

- A per-caller authorization model. Once the toggle is on, every app on
  the device can fire the intents. Users who need finer control can fork
  the receiver and add an `android:permission` attribute that gates by a
  custom `signature`-protected permission their automation app holds.
  That's intentionally not the default — it would break Tasker for the
  median user.
- Encrypted-in-transit. Broadcasts are local IPC; there is no transport
  to attack. The threat is a malicious app on the same device, which
  the opt-in toggle is the answer to.
