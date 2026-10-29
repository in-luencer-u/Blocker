package app.guardian.shield.ui

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import app.guardian.shield.R
import app.guardian.shield.admin.DeviceOwnerManager
import app.guardian.shield.core.Prefs

/**
 * The honest list of what this app cannot do.
 *
 * Shipped as a first-class screen rather than buried in a README, because a
 * blocker that oversells itself is worse than no blocker: the user stops
 * planning around the holes they still have.
 */
class LimitationsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        render()
    }

    private fun render() {
        val root = verticalRoot()

        root.addView(titleText("What this cannot block"))
        root.addView(spacer(8))
        root.addView(bodyText(
            "Read this once, properly. An honest picture of the gaps is more useful " +
                "than a promise that does not hold."
        ))
        root.addView(spacer(18))

        if (!DeviceOwnerManager.isDeviceOwner(this)) {
            root.addView(deviceOwnerCard())
        } else {
            root.addView(card(accentRes = R.color.ok).apply {
                addView(headingText("Device owner is active"))
                addView(spacer(6))
                addView(bodyText(
                    "This is the strongest configuration a non-rooted Android phone " +
                        "allows. Uninstalling, switching off the VPN, safe mode, factory " +
                        "reset and USB debugging are all blocked by the system itself, " +
                        "not just by this app."
                ))
            })
        }

        gap(
            root,
            "Safe mode",
            "Holding the power button and long-pressing \"Power off\" can start " +
                "Android in safe mode, where third-party apps and accessibility " +
                "services do not run. The filter is off for as long as the phone stays " +
                "in safe mode.\n\nClosed only by device owner mode, which disables safe " +
                "boot outright."
        )

        gap(
            root,
            "Factory reset and recovery",
            "A factory reset wipes this app along with everything else. So does " +
                "flashing the phone from a computer. Nothing installed as an app can " +
                "prevent that.\n\nDevice owner mode blocks factory reset from Settings, " +
                "but not the hardware recovery menu."
        )

        gap(
            root,
            "A second VPN app",
            "Android allows one VPN at a time. Installing another VPN and starting " +
                "it replaces this filter, and Android asks for no password to do it. " +
                "Shield notices, logs it, and alerts your partner, but it cannot stop " +
                "it.\n\nClosed by device owner mode, which pins Shield as an always-on " +
                "VPN with lockdown, so nothing else can take the slot."
        )

        gap(
            root,
            "Encrypted DNS inside an app",
            "Filtering works by answering name lookups. An app that ships its own " +
                "encrypted resolver on a hardcoded address can look names up without " +
                "asking the system.\n\nShield closes the known routes: lookups for the " +
                "public encrypted-DNS providers are refused, the resolver addresses " +
                "themselves are captured, and HTTPS records are refused so Encrypted " +
                "Client Hello cannot hide a hostname. A new provider nobody has seen " +
                "before would work until its address is added to the list."
        )

        gap(
            root,
            "Content inside apps that host everything together",
            "A social feed serves ordinary posts and adult posts from the same few " +
                "hostnames. Domain filtering cannot tell them apart, and the " +
                "on-screen guard only catches explicit text and titles — not an image " +
                "with no caption.\n\nThe only reliable answer is to block the whole app. " +
                "Use \"Block specific apps\" for the ones that are a problem for you."
        )

        gap(
            root,
            "Images and video are not inspected",
            "Shield does not run image recognition on your screen. Doing it well " +
                "needs a large model running constantly, which would drain the battery " +
                "and still miss plenty. Rather than ship something that half works and " +
                "feels like protection, this is left out.\n\nWhat covers it instead: " +
                "forced SafeSearch on Google, Bing, DuckDuckGo and Yandex, and " +
                "restricted mode on YouTube, so image and video search results are " +
                "filtered at the source."
        )

        gap(
            root,
            "Another device",
            "A laptop, a tablet, a work phone, or someone else's phone is outside " +
                "this app entirely. If that is a realistic route for you, set up " +
                "filtering on your home router as well, and tell your partner it is " +
                "part of the plan."
        )

        gap(
            root,
            "Second users and work profiles",
            "A guest session or a second user does not run this app. Device owner " +
                "mode blocks adding users; without it, the gap stays open."
        )

        gap(
            root,
            "Root and an unlocked bootloader",
            "If the phone is rooted, or you unlock the bootloader later, every " +
                "protection here can be removed. There is no way around that, and it " +
                "is true of every blocker, paid ones included."
        )

        gap(
            root,
            "Alerts need a phone signal",
            "Partner alerts are sent as ordinary text messages, directly from this " +
                "phone, so that nothing has to pass through a server. With no signal, " +
                "or with SMS permission refused, an alert is recorded on the phone but " +
                "your partner is not told until later."
        )

        root.addView(spacer(8))
        root.addView(card(accentRes = R.color.accent).apply {
            addView(headingText("What is genuinely solid"))
            addView(spacer(8))
            addView(bodyText(
                "• Every browser, every private and incognito tab, and every in-app " +
                    "browser resolves names through Shield. There is no per-browser " +
                    "setting to forget.\n\n" +
                    "• SafeSearch and YouTube restricted mode are enforced at the DNS " +
                    "level, so they cannot be switched off in the app or on the site.\n\n" +
                    "• The block list is refreshed daily and anything you report is " +
                    "blocked instantly.\n\n" +
                    "• Turning protection off takes ${Prefs.cooldownHours} hours of " +
                    "waiting plus a code only your partner has. Switching it back on is " +
                    "instant."
            ))
        })

        root.addView(spacer(12))
        root.addView(secondaryButton("Back") { finish() })
        setScrollingContent(root)
    }

    private fun deviceOwnerCard(): LinearLayout = card(accentRes = R.color.warn).apply {
        addView(headingText("The one change that makes this unbreakable"))
        addView(spacer(8))
        addView(bodyText(
            "Right now Shield is an ordinary app. However many layers it has, " +
                "someone determined enough can tap through Settings and remove it.\n\n" +
                "Android has a stronger mode called device owner. In that mode the " +
                "system itself refuses to uninstall the app, refuses to change VPN " +
                "settings, and disables safe mode, factory reset and USB debugging. " +
                "That is the configuration you actually want.\n\n" +
                "It cannot be granted from inside the app — by design, or malware " +
                "would use it. It has to be set once from a computer, on a phone with " +
                "no Google or vivo account signed in, which in practice means right " +
                "after a factory reset."
        ))
        addView(spacer(12))
        addView(headingText("How to do it"))
        addView(spacer(6))
        addView(bodyText(
            "1. Back up anything you care about — this needs a factory reset\n" +
                "2. Reset the phone and skip adding any account during setup\n" +
                "3. Install Shield (the APK, not from a store)\n" +
                "4. Enable USB debugging in Developer options\n" +
                "5. From a computer with adb installed, run:\n\n" +
                "adb shell dpm set-device-owner \\\n" +
                "  app.guardian.shield/.admin.ShieldDeviceAdminReceiver\n\n" +
                "6. Open Shield and finish setup. Then sign your accounts back in.\n\n" +
                "Ask your partner to run the command and keep it to themselves. If you " +
                "do it yourself you will know how to undo it."
        ))
        addView(spacer(10))
        addView(mutedText(
            "Once device owner is set, removing it needs a factory reset — which is " +
                "exactly the point. The full instructions are also in SETUP.md in the " +
                "source repository."
        ))
    }

    private fun gap(root: LinearLayout, title: String, body: String) {
        root.addView(card().apply {
            addView(headingText(title))
            addView(spacer(6))
            addView(bodyText(body))
        })
    }
}
