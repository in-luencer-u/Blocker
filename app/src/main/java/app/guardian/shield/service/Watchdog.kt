package app.guardian.shield.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Keeps the filter alive on a phone whose vendor actively tries to kill it.
 *
 * OriginOS will stop background work aggressively, so no single mechanism is
 * trusted. Four independent ones overlap:
 *
 *  - the VPN service is a foreground service, the hardest thing to kill;
 *  - a periodic WorkManager job re-checks every 15 minutes;
 *  - an AlarmManager alarm handles the gap right after a kill;
 *  - the accessibility service, which the system restarts on its own, calls
 *    [ensureEverything] whenever it sees a screen change.
 *
 * Each one alone is defeatable. Together they close the window to minutes at
 * worst, and the gap is recorded so it is visible rather than silent.
 */
object Watchdog {

    private const val PERIODIC_WORK = "shield-watchdog"
    private const val UPDATE_WORK = "shield-blocklist-update"
    private const val ALARM_REQUEST = 7311
    private const val RESTART_DELAY_MS = 4_000L
    private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L

    fun schedule(context: Context) {
        schedulePeriodicWork(context)
        scheduleBlocklistUpdates(context)
        armAlarm(context, HEARTBEAT_INTERVAL_MS)
    }

    private fun schedulePeriodicWork(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
                    .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .build()
            )
        }
    }

    fun scheduleBlocklistUpdates(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UPDATE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(24, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .build()
                    )
                    .build()
            )
        }
    }

    fun updateBlocklistNow(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<BlocklistUpdateWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }
    }

    /** Called when protection has just gone down and should come back now. */
    fun requestRestart(context: Context) {
        armAlarm(context, RESTART_DELAY_MS)
        runCatching {
            WorkManager.getInstance(context)
                .enqueue(OneTimeWorkRequestBuilder<WatchdogWorker>().build())
        }
    }

    private fun armAlarm(context: Context, delayMillis: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            Intent(context, WatchdogAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + delayMillis

        runCatching {
            // Exact alarms need a user-granted permission on recent Android, so
            // fall back to the inexact variant rather than crashing.
            val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarms.canScheduleExactAlarms()
            if (canUseExact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }.onFailure {
            runCatching {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }
    }

    /**
     * The single entry point every persistence mechanism funnels into: bring any
     * missing layer back up, and re-arm the next check.
     */
    fun ensureEverything(context: Context) {
        app.guardian.shield.vpn.VpnController.ensureRunning(context)
        app.guardian.shield.admin.DeviceOwnerManager.applyPolicies(context)
        armAlarm(context, HEARTBEAT_INTERVAL_MS)
    }
}
