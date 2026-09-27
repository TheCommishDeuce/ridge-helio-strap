# Getting your strap's auth key

Ridge talks to the strap directly over Bluetooth, but the strap only answers someone who
knows its **auth key**: 16 bytes that the official Zepp app created when it paired the
strap. Ridge cannot pair a strap itself. No known method makes a Zepp OS device accept a
key we generate. So you need the key the Zepp app made, **once per pairing**.

`keyfetch.py` signs in to your Zepp account, reads the MAC address and key of every device
bound to it, and prints them. It runs on your computer, not on the phone. The Ridge app
contains no Zepp code and never talks to the Zepp cloud.

## Before you start

1. Pair the strap with the **official Zepp app** as usual (this creates the key), and let
   it install any pending firmware update.
2. Install [uv](https://docs.astral.sh/uv/). It fetches Python and the script's three
   dependencies by itself.

## Run it

```sh
uv run tools/keyfetch/keyfetch.py you@example.com --qr
```

It asks for your Zepp password without echoing it, then prints one line per device:

```
Amazfit Helio Strap  mac=AA:BB:CC:DD:EE:FF  key=0123…(32 hex)  (active)
```

With `--qr` it also prints a QR code that holds the MAC and key together.

**In Ridge's setup** (or later: the gear → Settings → Strap), either paste the MAC and the 32-character key into their
fields, or point your phone's camera (or Google Lens) at the QR code, copy the text it
shows (`{"v":1,"mac":…,"key":…}`) and paste all of it into the key field. The key is kept
in the Android keystore.

## What to know

- **Sign-in resets the Zepp app.** Zepp allows one session per account, so the Zepp app
  asks you to sign in again next time you open it. The key is not affected.
- **Your password goes only to Zepp**, over HTTPS. The script stores nothing and never
  prints the password or the session tokens. The key it prints is the one secret: anyone
  with it and Bluetooth range can read your strap. Treat it like a password.
- **Signing in with a Google, Apple or Facebook account is not supported.** The script uses
  email and password. Setting a password on the Zepp account first may work (untested).
- **The key changes when the strap is paired again** (after a factory reset, or pairing it
  to another phone through the Zepp app). Ridge then says "The strap rejected the key".
  Run the script again and pair again with the new key.
- **It depends on the Zepp cloud API**, which is undocumented and can change. The script
  says so by name when a reply is not what it expects. It has been used successfully on
  one account.
- **Already use Gadgetbridge?** Its device export holds the same key
  (`Export_preference_device.xml`, `authkey`), and you can use that instead.

## Keeping the Zepp app out of the way

Only one app can talk to the strap at a time. Keep the Zepp app installed (it is the only
way to update the firmware) but stop it from grabbing the strap: revoke its **Nearby
devices** permission, or force-stop it.

**Updating the firmware**, deliberately and not automatically:

1. Don't sync in Ridge meanwhile (syncing is manual, so just don't press it).
2. Give Zepp its Nearby devices permission back, open it, and let it update the strap.
3. Take the permission away again (or force-stop Zepp).
4. Sync in Ridge. If it says "The strap rejected the key", run this script again.

A firmware update can also change the data format. If a sync after an update looks wrong,
please open an issue with the firmware version (Zepp app → the strap → About).

## Credits

The sign-in request format was learned from the `huami_token` project and
Gadgetbridge's documentation. See `NOTICE`.
