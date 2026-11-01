# TV Pad

Use your phone as a gamepad for your TV (built for Mi TV / Android TV).

Two apps, both built automatically by GitHub Actions:

- **Phone app** (`app/`): Bluetooth gamepad (full gamepad with two analog sticks, D-pad, A/B/X/Y,
  L1/R1/L2/R2, Select/Start/Home) and a Wi-Fi gamepad over ADB (buttons only).
- **TV Pad Helper** (`tv/`): install on the TV. Shows the TV's IP address, how to pair, and a live
  display of every button the phone sends, so you can test the connection.

## Get the APKs
Actions tab > latest **Build APKs** run > download **TVPad-phone-apk** and **TVPad-TV-helper-apk**.
Unzip each to get the `.apk`.

## Bluetooth mode
1. TV: Settings > Remotes & Accessories > Add accessory.
2. Phone: TV Pad > Bluetooth gamepad > Make phone visible.
3. Choose the phone on the TV and accept pairing. The pad opens when connected.

## Wi-Fi (ADB) mode
1. TV: turn on Developer options > USB debugging.
2. Phone: TV Pad > Wi-Fi gamepad > type the TV's IP (shown in TV Pad Helper) > Connect.
3. Tap Allow on the TV (tick Always allow).
