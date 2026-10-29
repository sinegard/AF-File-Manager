package com.affilemanager.app.network;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import com.affilemanager.app.terminal.PrivilegedTerminalRuntimeVerifier;
import com.affilemanager.app.media.BackgroundPlaybackRuntimeVerifier;

import java.util.Arrays;

public final class OptimizedSftpInstrumentation extends Instrumentation {
    private Bundle testArguments;

    @Override
    public void onCreate(Bundle arguments) {
        testArguments = arguments == null ? new Bundle() : new Bundle(arguments);
        super.onCreate(arguments);
        if (("privileged".equals(testArguments.getString("afSuite")) ||
                "privileged-editor".equals(testArguments.getString("afSuite"))) &&
                "true".equals(testArguments.getString("afSelectShizuku"))) {
            if (!getTargetContext().getSharedPreferences("advanced_access", android.content.Context.MODE_PRIVATE)
                    .edit().putString("selected_mode", "SHIZUKU").commit()) {
                throw new IllegalStateException("Owned provider-mode setup was not persisted");
            }
        }
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        String suite = testArguments.getString("afSuite", "sftp");
        char[] password = new char[0];
        try {
            boolean verified;
            if ("media-player".equals(suite)) {
                verified = com.affilemanager.app.MediaPlayerRuntimeVerifier.verify(this);
            } else if ("vault".equals(suite)) {
                verified = com.affilemanager.app.security.VaultRuntimeVerifier.verify(this);
            } else if ("ftps-trust".equals(suite)) {
                verified = FtpTlsUiRuntimeVerifier.verify(this);
            } else if ("smb-ui".equals(suite)) {
                password = required("afSmbAuditPassword").toCharArray();
                verified = SmbUiRuntimeVerifier.verify(this, Integer.parseInt(required("afSmbAuditPort")), password);
            } else if ("browser-issues".equals(suite)) {
                verified = com.affilemanager.app.BrowserIssuesRuntimeVerifier.verify(this);
            } else if ("document-workflow".equals(suite)) {
                verified = com.affilemanager.app.DocumentWorkflowRuntimeVerifier.verify(this);
            } else if ("sharing-controls".equals(suite)) {
                verified = com.affilemanager.app.transfer.SharingControlsRuntimeVerifier.verify(this,
                    testArguments.getString("afAuditCapturePrefix"));
            } else if ("transfer-options".equals(suite)) {
                verified = com.affilemanager.app.TransferOptionsRuntimeVerifier.verify(this);
            } else if ("media-editing".equals(suite)) {
                verified = com.affilemanager.app.media.editing.MediaEditingRuntimeVerifier.verify(this);
            } else if ("widgets".equals(suite)) {
                verified = com.affilemanager.app.widgets.WidgetRuntimeVerifier.verify(this)
                        && com.affilemanager.app.widgets.PinnedWidgetRuntimeVerifier.verify(this);
            } else if ("archive-navigation".equals(suite)) {
                verified = com.affilemanager.app.ArchiveRuntimeVerifier.verify(this);
            } else if ("share-index".equals(suite)) {
                launchTargetActivity();
                verified = com.affilemanager.app.transfer.ReleaseShareIndexVerifier.verify(this);
            } else if ("issue-regression".equals(suite)) {
                launchTargetActivity();
                verified = (Boolean) Class.forName("com.affilemanager.app.transfer.IssueRegressionRuntimeVerifier")
                        .getMethod("verify", Instrumentation.class).invoke(null, this);
            } else if ("nearby".equals(suite)) {
                launchTargetActivity();
                verified = com.affilemanager.app.transfer.NearbyRuntimeVerifier.verify(this);
            } else if ("transfer-diagnostics".equals(suite)) {
                launchTargetActivity();
                verified = com.affilemanager.app.transfer.NearbyRuntimeVerifier.verifyDiagnostics(this);
            } else if ("localization".equals(suite)) {
                launchTargetActivity();
                verified = com.affilemanager.app.transfer.NearbyRuntimeVerifier.verifyLocales(this);
            } else if ("split-apk".equals(suite)) {
                launchTargetActivity();
                verified = com.affilemanager.app.apk.SplitApkRuntimeVerifier.verify(this);
            } else if ("background-playback".equals(suite)) {
                launchTargetActivity();
                verified = BackgroundPlaybackRuntimeVerifier.verify(this);
            } else if ("protected-media".equals(suite)) {
                launchTargetActivity();
                verified = com.affilemanager.app.media.ProtectedMediaRuntimeVerifier.verify(this);
            } else if ("webdav".equals(suite)) {
                password = required("afWebDavPassword").toCharArray();
                verified = WebDavRuntimeVerifier.verify(
                        required("afWebDavHost"), Integer.parseInt(required("afWebDavPort")),
                        Boolean.parseBoolean(testArguments.getString("afWebDavTls", "false")),
                        required("afWebDavUsername"), password, required("afWebDavPath"),
                        testArguments.getString("afWebDavExpectedFile", ""),
                        testArguments.getString("afWebDavExpectedError", "")
                );
            } else if ("privileged".equals(suite)) {
                launchTargetActivity();
                verified = PrivilegedTerminalRuntimeVerifier.verifyAuthorizedBackend(
                        getTargetContext(), Integer.parseInt(required("afExpectedUid"))
                );
                result.putString("privilegedFileWrite", PrivilegedTerminalRuntimeVerifier.fileWriteCapability());
            } else if ("privileged-editor".equals(suite)) {
                verified = PrivilegedTerminalRuntimeVerifier.verifyAdvancedEditor(this);
            } else if ("root-service".equals(suite)) {
                verified = PrivilegedTerminalRuntimeVerifier.verifyRootServiceContract(getTargetContext());
            } else if ("terminal-failure".equals(suite)) {
                launchTargetActivity();
                verified = PrivilegedTerminalRuntimeVerifier.verifyImmediateFailure(getTargetContext());
            } else if ("sftp".equals(suite)) {
                password = required("afSftpPassword").toCharArray();
                verified = SftpRuntimeVerifier.verifyPasswordConnection(
                        required("afSftpHost"), Integer.parseInt(required("afSftpPort")),
                        required("afSftpUsername"), password, required("afSftpExpectedFile")
                );
            } else {
                throw new IllegalArgumentException("Unknown optimized test suite");
            }
            if (!verified) {
                throw new IllegalStateException("Optimized runtime check did not match its expected result");
            }
            result.putString("stream", "\nAF_OPTIMIZED_" + suite.toUpperCase(java.util.Locale.ROOT) + "_OK\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            try {
                android.graphics.Bitmap screen = getUiAutomation().takeScreenshot();
                if (screen != null) {
                    java.io.File file = new java.io.File(getTargetContext().getExternalFilesDir("validation"),
                            "audit-optimized-" + suite.replaceAll("[^a-zA-Z0-9-]", "_") + "-" + System.currentTimeMillis() + ".png");
                    try (java.io.OutputStream output = new java.io.FileOutputStream(file)) {
                        if (!screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) {
                            throw new IllegalStateException("Failed to record native failure pixels");
                        }
                    } finally { screen.recycle(); }
                    result.putString("failureScreenshot", file.getAbsolutePath());
                }
            } catch (Throwable capture) { error.addSuppressed(capture); }
            result.putString(
                    "stream",
                    "\nAF_OPTIMIZED_" + suite.toUpperCase(java.util.Locale.ROOT) + "_FAILED: " + error.getClass().getSimpleName()
                            + "\n" + Log.getStackTraceString(error) + "\n"
            );
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void launchTargetActivity() {
        // Graph-backed checks must wait for normal application initialization.
        startActivitySync(new Intent(Intent.ACTION_MAIN)
                .setClassName(getTargetContext(), "com.affilemanager.app.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private String required(String name) {
        String value = testArguments.getString(name);
        if (value == null || value.length() == 0) {
            throw new IllegalArgumentException("Missing instrumentation argument: " + name);
        }
        return value;
    }
}
