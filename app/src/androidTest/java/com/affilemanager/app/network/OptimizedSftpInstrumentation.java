package com.affilemanager.app.network;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import com.affilemanager.app.terminal.PrivilegedTerminalRuntimeVerifier;

import java.util.Arrays;

public final class OptimizedSftpInstrumentation extends Instrumentation {
    private Bundle testArguments;

    @Override
    public void onCreate(Bundle arguments) {
        testArguments = arguments == null ? new Bundle() : new Bundle(arguments);
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        String suite = testArguments.getString("afSuite", "sftp");
        char[] password = new char[0];
        try {
            boolean verified;
            if ("webdav".equals(suite)) {
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
