package com.affilemanager.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TransferPreferencesRepositoryTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val fixtureName = "transfer-preference-test-${System.nanoTime()}"
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences(fixtureName, mode)
    }
    private val raw get() = context.getSharedPreferences("ignored", Context.MODE_PRIVATE)

    @After fun cleanup() { check(base.deleteSharedPreferences(fixtureName)) }

    @Test fun absentPreferencesKeepExistingDefaults() {
        assertEquals(TransferPreferences(), UiPreferenceRepository(context).loadTransferPreferences())
    }

    @Test fun independentChoicesSurviveRecreationWithoutTouchingOtherSettings() {
        check(raw.edit().putString("share", "unrelated preference").commit())
        val chosen = TransferPreferences().withOptions(false,
            TransferOptions(ConflictPolicy.SKIP, TransferVerification.SHA256, TransferFailurePolicy.SKIP_AND_CONTINUE, false))
            .withOptions(true, TransferOptions(verification = TransferVerification.SIZE, askBeforeTransfer = false))
        UiPreferenceRepository(context).saveTransferPreferences(chosen)
        assertEquals(chosen, UiPreferenceRepository(context).loadTransferPreferences())
        assertEquals("unrelated preference", raw.getString("share", null))
        val stored = raw.getString("transfer_options", null)!!
        assertTrue(stored.length < 1_024)
        assertFalse(stored.contains("sourcePath"))
        assertFalse(stored.contains("destination"))
    }

    @Test fun malformedUnknownAndOversizedDataFailClosed() {
        for (value in listOf("broken", "{}", "{\"version\":99}", "x".repeat(4_097))) {
            check(raw.edit().putString("transfer_options", value).commit())
            assertEquals(TransferPreferences(), UiPreferenceRepository(context).loadTransferPreferences())
        }
    }

    @Test fun unknownEnumsAndWrongTypesCannotDisableConfirmation() {
        for (value in listOf(
            """{"conflictPolicy":"FUTURE","verification":"SIZE","failurePolicy":"STOP","askBeforeTransfer":false}""",
            """{"conflictPolicy":"SKIP","verification":"FUTURE","failurePolicy":"STOP","askBeforeTransfer":false}""",
            """{"conflictPolicy":"SKIP","verification":"SIZE","failurePolicy":"FUTURE","askBeforeTransfer":false}""",
            """{"conflictPolicy":"SKIP","verification":"SIZE","failurePolicy":"STOP","askBeforeTransfer":"false"}""",
        )) {
            check(raw.edit().putString("transfer_options", """{"version":1,"copy":$value,"move":$value}""").commit())
            assertEquals(TransferPreferences(), UiPreferenceRepository(context).loadTransferPreferences())
        }
    }

    @Test fun unsafePersistedMoveSettingsAreNormalizedAndDoNotOverwriteSilently() {
        check(raw.edit().putString("transfer_options", """{"version":1,"move":{"conflictPolicy":"REPLACE","verification":"SHA256","failurePolicy":"SKIP_AND_CONTINUE","askBeforeTransfer":false}}""").commit())
        val loaded = UiPreferenceRepository(context).loadTransferPreferences()
        assertTrue(loaded.optionsFor(true).askBeforeTransfer)
        assertEquals(TransferFailurePolicy.STOP, loaded.optionsFor(true).failurePolicy)
        assertEquals(TransferPreferences().copy, loaded.copy)
    }

    @Test fun aFailedCommitIsReportedAndDoesNotPretendToSave() {
        val failingContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = object : SharedPreferences by raw {
                override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by raw.edit() {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
                    override fun commit(): Boolean = false
                }
            }
        }
        val failure = runCatching {
            UiPreferenceRepository(failingContext).saveTransferPreferences(TransferPreferences())
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(raw.contains("transfer_options"))
    }
}
