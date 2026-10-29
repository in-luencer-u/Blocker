# Setting up Shield on a vivo Y400 5G (OriginOS 6 / Android 16)

Work through this in order. The app's own setup screen mirrors these steps and
shows a live status for each one, so you can always see what is still missing.

Read **[Part C](#part-c--the-one-change-that-makes-it-unbreakable)** before you
start, because the strongest option requires a factory reset and is much easier
to do now than later.

---

## Part A — install

1. Copy `app/release/Shield-1.0.0.apk` to the phone.
2. Open it in Files and tap **Install**. OriginOS will warn about installing from
   an unknown source and may run a security scan; allow it.
3. If install is blocked entirely, enable
   **Settings > More settings > Install unknown apps** for whichever app you are
   installing from (Files, Chrome, WhatsApp).
4. Open Shield. It goes straight to the setup checklist.

---

## Part B — permissions and OriginOS settings

### 1. Why you are doing this

Write a sentence in your own words. It is shown back to you on the urge screen,
which is exactly when you will not want to read it. It stays on the phone.

### 2. Content filter — required

Tap **Turn on the filter** and accept Android's VPN warning ("Shield wants to set
up a VPN connection"). The warning is unavoidable: Android shows it for anything
using `VpnService`.

Shield does not route your traffic anywhere. It answers DNS lookups on the device
and refuses the blocked ones. A small key icon appears in the status bar while it
is running.

### 3. On-screen guard — required

Tap **Turn the guard on**, find **Shield on-screen guard** under *Downloaded apps*
or *Installed services*, and enable it.

**If the switch is greyed out** — this is the step most people give up on.
Android 13 and later block accessibility for apps installed outside the Play
Store until you allow it explicitly:

1. Settings > Apps > Shield (or tap **Open this app's info page** in Shield)
2. Tap the **three dots** at the top right
3. Tap **Allow restricted settings**
4. Go back to accessibility settings; the switch now works

### 4. Display over other apps

Needed for the urge-support screen to appear on top of whatever was blocked.
Blocking still works without it — you just see the page fail instead of a calming
screen.

### 5. Notifications

Android needs this to let the filter run permanently. The notification is
low-priority and silent.

### 6. Battery — Android's own setting

Allow **unrestricted** battery use. Without it Android stops the filter in the
background.

### 7. OriginOS auto-start — the step that actually matters on this phone

OriginOS keeps its **own** kill lists, separate from Android's battery
optimisation. An app can be exempt from Android's and still be killed by
OriginOS. This is the usual reason a blocker quietly stops working on a vivo
phone after a day or two.

Set both:

```
Settings > More settings > Applications > Autostart
   └── turn Shield ON

Settings > Battery > Background power consumption
   └── Shield > Allow high background power consumption
```

Then **lock the app in recents**: open Recent apps, swipe **down** on the Shield
card (or long-press it) and tap the padlock. Clearing recents will no longer close
it.

> Exact wording moves between OriginOS versions. If a path does not exist, search
> the Settings app for "autostart", "background" or "power". The reference at
> [dontkillmyapp.com/vivo](https://dontkillmyapp.com/vivo) tracks these changes.

### 8. Private DNS — check this

```
Settings > More connections > Private DNS
```

Set it to **Automatic** or **Off**. If it is set to a *custom hostname*, the phone
sends lookups straight out over DoT, around Shield. The app's setup screen reads
this setting and flags it in red if it is wrong.

### 9. Uninstall protection

Tap **Turn on uninstall protection** and confirm the device-admin prompt. Android
then refuses to uninstall Shield until admin is deactivated — and the on-screen
guard blocks that screen and texts your partner when you try.

This is a speed bump, not a wall. Part C is the wall.

### 10. Accountability partner

Have the person physically with you if you can.

1. Enter their name and phone number.
2. Shield shows a **setup key, once**, on a screen that blocks screenshots.
3. They add it to any authenticator app — Google Authenticator, Aegis, FreeOTP —
   as a time-based 6-digit code named "Shield".
4. They read you the code their app is showing. Type it in to prove it was stored
   correctly.
5. Grant the SMS permission so they get tamper alerts.

After this the key is sealed into the phone's hardware keystore and **cannot be
displayed again, including to you**. That is the point: you cannot generate your
own approval codes later.

> Ideally, let your partner do steps 1–4 themselves and not tell you the key.

### 11. Finish

Tap **Finish setup and lock it in**. The 24-hour cooling-off rule is now active.

---

## Part C — the one change that makes it unbreakable

Everything above can, with enough determination, be tapped through in Settings.
Android has a stronger mode called **device owner**, in which the system itself
refuses to cooperate:

- uninstalling Shield is blocked outright
- VPN settings cannot be changed, and Shield is pinned as always-on with lockdown
  (no traffic flows at all unless the filter is up)
- **safe mode is disabled** — otherwise a reboot into safe mode turns the guard off
- factory reset from Settings is disabled
- USB debugging is disabled, so nothing can be switched off from a computer
- adding a second user or guest session is blocked
- Private DNS is forced to a family-filtering resolver

Device owner **cannot be granted from inside the app** — by design, or malware
would use it. It must be set once over ADB on a phone with **no accounts signed
in**, which in practice means straight after a factory reset.

### Steps

1. **Back up anything you care about.** This requires a factory reset.
2. Factory reset the phone. During setup, **skip adding any Google or vivo
   account** and skip restoring a backup.
3. Install Shield from the APK.
4. Enable developer options: Settings > About phone > tap **Software version**
   seven times. Then Settings > More settings > Developer options > **USB
   debugging**.
5. On a computer with `adb` installed, connect the phone, accept the debugging
   prompt, and run:

   ```bash
   adb devices   # confirm the phone is listed
   adb shell dpm set-device-owner app.guardian.shield/.admin.ShieldDeviceAdminReceiver
   ```

   Success looks like:
   ```
   Success: Device owner set to package app.guardian.shield
   ```

6. Open Shield and finish the setup checklist. The dashboard will show
   **"Device owner — uninstall and VPN changes are blocked by the system"**.
7. Now sign your accounts back in and restore your data.

### Have your partner run the command

If you do it yourself, you will know exactly how to undo it. Let your partner run
step 5 and keep the method to themselves.

### Undoing it

Removing device owner needs a factory reset — which is exactly the point. If you
reach that point, the honest move is to talk to your partner first.

### If the command fails

| Error | Cause |
|---|---|
| `Not allowed to set the device owner because there are already some accounts on the device` | An account is signed in. Remove every account, or factory reset again. |
| `device owner already set` | Already done. |
| `adb: no devices found` | USB debugging off, cable is charge-only, or the debugging prompt was not accepted. |

---

## Verifying it works

| Check | Expected |
|---|---|
| Open a known adult site in Chrome | Page fails to load; urge screen appears |
| Repeat in an **incognito** tab | Same — the filter is below the browser |
| Repeat in the vivo browser and inside Instagram's in-app browser | Same |
| Google Images, search anything mildly suggestive | Results filtered; "SafeSearch locked by your network" |
| YouTube | Restricted mode on and not switchable |
| Settings > Apps > Shield > Uninstall | Bounced out; partner gets a text |
| Turn the screen off for an hour, then check the dashboard | Filter still active, no gap logged |
| Reboot the phone | Filter active within a few seconds of unlocking |
| Shield > Request to unlock | 24-hour countdown; cannot be shortened |

If the dashboard ever shows a layer in red, tap its button — each one fixes itself.

---

## Day-to-day

**Something legitimate is blocked.** Shield > *Report a site* also lists what you
have blocked. To **allow** something you need an unlock window; this is
deliberate, because "I need it for work" is the most common bypass story.
Most false positives are better handled by reporting the problem to the
upstream list.

**Something got through.** Shield > *Report something that got through* > type the
domain. Blocked instantly, everywhere, with no waiting period.

**An app mixes adult content with normal content.** Shield > *Block specific apps*.
Blocking is instant; unblocking needs the full unlock process.

**You genuinely need to turn it off.** Shield > *Request to unlock*, write the real
reason, wait 24 hours, then ask your partner for their code. You get 10 minutes.
Cancelling is one tap at any point.
