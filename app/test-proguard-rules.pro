# The optimized release check uses a minimal framework Instrumentation instead
# of AndroidJUnitRunner, so the target APK remains identical to the user build.
# This rule affects only the test APK.
-keep class com.affilemanager.app.network.OptimizedSftpInstrumentation { *; }

# The target and the independently optimized harness share a class loader.
# Give test-only classes a private namespace so their short names cannot shadow
# classes in the APK being verified. This does not change the shipping APK.
-repackageclasses com.affilemanager.app.test.internal
