# The optimized release check uses a minimal framework Instrumentation instead
# of AndroidJUnitRunner, so the target APK remains identical to the user build.
# This rule affects only the test APK.
-keep class com.affilemanager.app.network.OptimizedSftpInstrumentation { *; }
