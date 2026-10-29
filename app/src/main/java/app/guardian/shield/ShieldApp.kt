package app.guardian.shield

import android.app.Application
import app.guardian.shield.core.Notifications
import app.guardian.shield.core.Prefs
import app.guardian.shield.data.EventStore
import app.guardian.shield.dns.Blocklist
import app.guardian.shield.service.Watchdog

class ShieldApp : Application() {

    override fun onCreate() {
        super.onCreate()

        Prefs.init(this)
        Notifications.ensureChannels(this)

        // Loading the blocklist touches disk, so keep it off the main thread.
        Thread {
            Blocklist.ensureLoaded(this)

            if (Prefs.onboardingComplete) {
                // Record the first day we ever ran, so the streak has an origin.
                if (Prefs.streakStartDay == 0) {
                    Prefs.streakStartDay = EventStore.dayIndex(System.currentTimeMillis())
                }
                Watchdog.ensureEverything(this)
                Watchdog.schedule(this)
            }
        }.apply { isDaemon = true }.start()
    }
}
