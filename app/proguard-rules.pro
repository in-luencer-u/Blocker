# Minification is off for this build: the release APK is sideloaded onto one
# phone, size is not a concern, and readable stack traces matter more when the
# only way to diagnose a problem is a bug report from the person using it.
#
# These rules are kept so that turning minification on later does not silently
# break the components the system instantiates by name.

# Entry points the framework creates reflectively from the manifest.
-keep class app.guardian.shield.ShieldApp { *; }
-keep class app.guardian.shield.vpn.ShieldVpnService { *; }
-keep class app.guardian.shield.guard.GuardAccessibilityService { *; }
-keep class app.guardian.shield.admin.ShieldDeviceAdminReceiver { *; }
-keep class app.guardian.shield.service.** { *; }
-keep class app.guardian.shield.ui.** { *; }

# WorkManager instantiates Workers by class name.
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# Views built in code and referenced from themes.
-keep public class * extends android.view.View { *; }
