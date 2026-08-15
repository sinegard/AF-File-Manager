package com.affilemanager.app.network;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;

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
        char[] password = required("afSftpPassword").toCharArray();
        try {
            boolean verified = SftpRuntimeVerifier.verifyPasswordConnection(
                    required("afSftpHost"),
                    Integer.parseInt(required("afSftpPort")),
                    required("afSftpUsername"),
                    password,
                    required("afSftpExpectedFile")
            );
            if (!verified) {
                throw new IllegalStateException("Expected SFTP entry was not listed");
            }
            result.putString("stream", "\nAF_OPTIMIZED_SFTP_OK\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString(
                    "stream",
                    "\nAF_OPTIMIZED_SFTP_FAILED: " + error.getClass().getSimpleName()
                            + "\n" + Log.getStackTraceString(error) + "\n"
            );
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private String required(String name) {
        String value = testArguments.getString(name);
        if (value == null || value.length() == 0) {
            throw new IllegalArgumentException("Missing instrumentation argument: " + name);
        }
        return value;
    }
}
