# TV Pad Wi-Fi

A Wi-Fi remote for Android TV / Google TV (including Mi TV A-series). It uses the same
Android TV Remote protocol v2 as the Google TV app and Zank Remote, so there is no Bluetooth pairing.

## Get the APK
1. Push this repo to GitHub.
2. Open the **Actions** tab, then the latest **Build APK** run.
3. Download the **TVPad-WiFi-debug-apk** artifact, unzip it and install `app-debug.apk` on your phone.

(Or open the folder in Android Studio and press Run.)

## Use
1. Put the phone and TV on the same Wi-Fi.
2. Open the app. Your TV should appear under "TVs found". Otherwise type its IP address
   (TV: Settings > Network).
3. Tap the TV. A 6 character code appears on the TV. Type it in the app. You only do this once.
4. Use the remote.

## How it works
- mDNS discovery of `_androidtvremote2._tcp` (Discovery.kt)
- TLS pairing on port 6467 using a client certificate kept in the Android KeyStore (TvProtocol.kt)
- Remote key presses on port 6466 (TvProtocol.kt)
- Protobuf is hand encoded in Proto.kt, so there are no code generation steps.

The TV's own certificate is not validated (it is self-signed), so use this on a network you trust.
