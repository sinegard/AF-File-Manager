# Only the disposable benchmark variant exposes this framework-instrumentation entry point.
# Do not retain coroutine libraries or app internals broadly for a test.
-keep class com.affilemanager.app.transfer.IssueRegressionRuntimeVerifier {
    public static boolean verify(android.app.Instrumentation);
}
