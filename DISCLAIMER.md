# Disclaimer

**portal-commons is an independent, community-built project. It is not affiliated
with, authorized by, endorsed by, or sponsored by Meta Platforms, Inc.**

"Meta", "Meta Portal", and "Portal" are trademarks of Meta Platforms, Inc. They
are used here only to identify the hardware the apps that consume this library
are compatible with (nominative use). portal-commons is not a Meta product and
ships no Meta code.

## Use at your own risk

portal-commons is a shared library. It does not modify your device on its own —
it is consumed by wake/voice apps that you build and install yourself. Meta
Portal devices are discontinued and receive no official support. By using this
library and the apps built on it you accept that:

- Installing and running third-party apps on a device may **void any remaining
  warranty** or violate the device's terms of use.
- Modifying a device or sideloading software always carries some risk. We are
  not aware of this library causing any harm, but **no outcome is guaranteed**.
- You are responsible for the apps you choose to build, install, and run.
  portal-commons does not vet the apps that depend on it.

The software is provided "AS IS", without warranty of any kind, under the terms
of the [MIT License](LICENSE). To the maximum extent permitted by law, the
authors and contributors accept no liability for any damage, data loss, or other
harm arising from its use.

## Privacy

portal-commons has no analytics and no accounts. It is a self-contained library
that performs no network communication of its own. The `DebugLog` helper writes a
best-effort local log file (`files/debug.txt`) on the device only. No personal
data is collected by the project.

## Reporting issues

If you believe any content here infringes your rights, or you represent Meta and
have concerns, please open an issue or contact the maintainers; we will respond
promptly.
