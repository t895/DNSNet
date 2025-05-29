<div align="center">
  <img src="assets/feature-graphic.png" alt="DNSNet feature graphic" width="50%"/>
</div>

Based on DNS66, DNSNet aims to continue the goals of the original
app with modern Android development practices.

This is a DNS-based host blocker for Android. In the default configuration,
several widely-respected host files are used to block ads, malware, and other
weird stuff.

<div align="center">
<a href="https://hosted.weblate.org/engage/dnsnet/">
<img src="https://hosted.weblate.org/widget/dnsnet/287x66-black.png" alt="Translation status" height="80" />
</a>
</div>

Screenshots
-----------

<div align="center">
<img src="metadata/en-US/images/phoneScreenshots/start-p9p.png" width="20%" /> <img src="metadata/en-US/images/phoneScreenshots/hosts-p9p.png" width="20%" /> <img src="metadata/en-US/images/phoneScreenshots/apps-p9p.png" width="20%" /> <img src="metadata/en-US/images/phoneScreenshots/dns-p9p.png" width="20%" />
</div>

Installing
----------
<div align="center">
<a href="https://f-droid.org/packages/dev.clombardo.dnsnet/">
<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">
</a>
<a href="https://play.google.com/store/apps/details?id=dev.clombardo.dnsnet">
<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" alt="Get it on Google Play" height="80">
</a>
<a href="https://accrescent.app/app/dev.clombardo.dnsnet">
<img src="https://accrescent.app/badges/get-it-on.png" alt="Get it on Accrescent" height="80">
</a>
</div>

Or download the latest APK from the [Releases Section](https://github.com/t895/DNSNet/releases/latest).

How it works
------------
The app establishes a "VPN (Virtual Private Network) service." Traditionally, these are intended to
send internet traffic from your device to a remote server with the intent of anonymizing the source
of your requests. In the case of DNSNet, this is not the case.

DNSNet uses Android's "VPN service" API as a way to read and filter your internet traffic entirely
on-device. It starts by getting access to a "tunnel" that provides your network requests. Then, it
reads the hostname (e.g. google.com) of each DNS request. Finally, it blocks or allows each request
based on the configuration as seen in the "Filters" screen of the app.

It's important to note that this approach is not perfect and has some notable downsides:
* While this app has been tuned to be as efficient as possible, it is still a service that must run
constantly and will have some battery cost over alternate methods of blocking through services like
AdAway with root, Rethink, and others.
* Since this runs as a "VPN service," you will be unable to run another "VPN service" alongside it
since Android only allows for one at a time.

For further information, see the [FAQ](https://github.com/t895/DNSNet/wiki/FAQ).

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
- Java 17+
- [Android Studio](https://developer.android.com/studio) (Optionally)

Building on Windows is currently broken due to issues with compiling quiche, the crate I use for making HTTP/3 requests.
Here's the related issue - https://github.com/cloudflare/quiche/issues/2020

Add all of the Rust build targets
```bash
rustup target add x86_64-linux-android i686-linux-android aarch64-linux-android armv7-linux-androideabi
```

Then run this in the root of the project to build the app
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

See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

Authors
-------
Charles Lombardo <clombardo169@gmail.com>

The app is based on the UI and services created by Julian Andres Klode <jak@jak-linux.org>

Parts are derived from https://github.com/dbrodie/AdBuster by Daniel Brodie.
