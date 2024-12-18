DNS-Based Host Blocking for Android
===================================
Based on DNS66, this projects aims to continue the goals of the original
app with modern Android development practices.

This is a DNS-based host blocker for Android. In the default configuration,
several widely-respected host files are used to block ads, malware, and other
weird stuff.

Installing
----------

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/dev.clombardo.dnsnet/)
[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
     alt="Get it on Google Play"
     height="80">](https://play.google.com/store/apps/details?id=dev.clombardo.dnsnet)
[<img src="https://accrescent.app/badges/get-it-on.png"
     alt="Get it on Accrescent"
     height="80">](https://accrescent.app/app/dev.clombardo.dnsnet)

Or download the latest APK from the [Releases Section](https://github.com/t895/DNSNet/releases/latest).

Using it
---------
There's also no validation of input, so DNS servers that are not valid IPv4
addresses are not rejected, neither are URLs for DNS server entries (we intend
to support URLs in the future, so you can point the app to a remote list of
servers).

How it works
------------
The app establishes a VPN service, with routes for all DNS servers diverted to
it. The VPN service then intercepts the packages for the servers and forwards
any DNS queries that are not blacklisted.

Custom upstream DNS can be configured. If the feature is turned off, the
current connection's DNS servers are used.

Privacy Guarantee
-----------------
Privacy is the most important aspect of DNSNet. Currently, DNSNet is strictly
data reducing: Running it can only reduce the amount of data leaving your
device, not increase it (except for fetching hosts files, obviously), as for
each request, we will either allow it to leave your device or not - we will
not send other requests or add other information to the request.

Contributing
------------
See [CONTRIBUTING.md](CONTRIBUTING.md)

Building
--------
You'll need a few things installed to get up and running
- [Rust](https://www.rust-lang.org/tools/install)
- [Python 3](https://www.python.org/downloads/)
- [Android Studio](https://developer.android.com/studio) (Optionally)

Add all of the Rust build targets
```bash
rustup target add x86_64-linux-android i686-linux-android aarch64-linux-android armv7-linux-androideabi
```

Then build
```bash
./gradlew assembleDebug
```

Note - Android Studio Ladybug on macOS has an issue where it won't be able to find "rustc" and "cargo" when building.
You'll need to build via the command line in order for things to work properly.

https://issuetracker.google.com/issues/377339196?pli=1

Alternatively, you can launch Android Studio with this command to workaround the issue.
```bash
open -na "Android Studio.app"
```

License
-------
This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the [License](COPYING), or
(at your option) any later version.

Code of Conduct
---------------
Please note that this project is released with a Contributor Code of
Conduct. By participating in this project you agree to abide by its terms.

Authors
-------
Charles Lombardo <clombardo169@gmail.com>

The app is based on the UI and services created by Julian Andres Klode <jak@jak-linux.org>

Parts are derived from https://github.com/dbrodie/AdBuster by Daniel Brodie.
