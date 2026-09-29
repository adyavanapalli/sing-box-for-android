# detour fork of sing-box for Android (SFA)

This fork adds one feature to SFA: after Android kills SFA, the VPN starts again by itself.
[detour](https://github.com/adyavanapalli/detour) installs this build on Android phones.

## The problem

Android kills an app when the app does not respond (ANR), or when the phone is short of memory.
After a kill, Android does not start SFA's VPN service again. With always-on VPN and lockdown,
the phone then has no network until the user opens SFA and taps Start.

`START_STICKY` does not correct this. When SFA's process dies, the kernel closes the tunnel.
The VPN code of Android sees this and drops its connection to the dead service. That call fails,
and Android then removes the service from the dead process (`ActiveServices.serviceProcessGoneLocked`).
A moment later, Android handles the death of the process, finds no service in it, and restarts nothing.

## The changes

All changes carry a `detour:` comment.

- `bg/GuardService.kt` (new): a small service in its own process, `:guard`. While the VPN runs,
  the main process starts and binds the guard, and it sends the guard a token: a Binder object
  that lives in the main process. When the token dies, the main process died, and the guard
  starts the VPN service again.
  - A stop by the user does not end the main process. Also, the main process stops the guard first.
  - The guard does not watch a binding to the VPN service. After a kill, Android keeps stale
    binding state for the service, and such a binding does not connect again.
  - The guard is sticky. If Android kills both processes, Android restarts the guard, and the guard
    starts the VPN service.
  - After 3 restarts in 10 minutes, or when a restart fails, the guard stops and posts an alert.
- `bg/BoxService.kt`: starts the guard when the VPN has started, and stops it before each stop.
  `onStartCommand` returns `START_STICKY`.
- `Application.kt`: the `:guard` process skips the setup of the app. It does not load libbox.
- `vendor/GitHubUpdateChecker.kt`: updates come from the releases of this fork.
- `app/build.gradle.kts`: `-PdetourDebuggable=true` makes a debuggable test build.
- `.github/workflows/detour.yml`: the build.

## Build a release

1. Open Actions, then "Build detour SFA", then "Run workflow".
2. Enter the sing-box tag, for example `v1.14.2`. It must match `version.properties`.
3. Enter the revision: `1` for the first detour build of an upstream version, then `2`, and more.
4. Select "publish" to make a release. The workflow never publishes a debuggable build.

The version code is the upstream code times 100, plus the revision. So each build replaces the one before.

The secrets `DETOUR_KEYSTORE` (the keystore, base64) and `DETOUR_KEYSTORE_PASS` hold the signing key.
Keep a copy of the keystore. Without it, a phone cannot update, and you must uninstall SFA first.

## Move to a new upstream version

1. Find the SFA commit of the new sing-box release:
   `git ls-tree <tag> clients/android` in the sing-box repository.
2. Rebase the `detour` branch onto that commit, and push it.
3. Run the workflow with the new tag and revision `1`.
