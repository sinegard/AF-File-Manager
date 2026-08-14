package com.affilemanager.app.editing

import com.affilemanager.app.network.RemoteClient
import com.affilemanager.app.network.RemotePath
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class RemoteEditSaver(private val sessions: EditSessionStore) {
    suspend fun saveOrigin(
        session: EditSession,
        client: RemoteClient,
        forceOverwrite: Boolean,
    ): EditSaveResult {
        val origin = session.origin as? EditOrigin.Remote ?: error("Edit session is not remote")
        val current = revision(client, origin, session)
        if (!forceOverwrite && !session.originRevision.hasSameContent(current)) {
            return EditSaveResult.Conflict(EditConflict(origin.label, session.originRevision, current))
        }
        client.upload(session.workingFile, origin.path, OperationContext.background())
        val verified = requireNotNull(revision(client, origin, session)) {
            "Serveris negrąžino išsaugoto failo patikrai"
        }
        require(session.workingRevision.hasSameContent(verified)) { "Išsaugojimo serveryje patikra nepavyko" }
        return EditSaveResult.Saved(verified)
    }

    private suspend fun revision(
        client: RemoteClient,
        origin: EditOrigin.Remote,
        session: EditSession,
    ): FileRevision? {
        val normalizedPath = RemotePath.normalize(origin.path)
        val parent = RemotePath.normalize("$normalizedPath/..")
        val remoteEntry = client.list(parent).firstOrNull {
            RemotePath.normalize(it.path) == normalizedPath
        } ?: return null
        require(!remoteEntry.directory) { "Pradiniame nuotoliniame kelyje dabar yra aplankas" }
        require(remoteEntry.sizeBytes in 0..EditLimits.MAX_FILE_BYTES) {
            "Nuotolinis failas pasikeitė ir dabar yra per didelis saugiai patikrai"
        }
        val verification = withContext(Dispatchers.IO) { sessions.verificationFile(session) }
        return try {
            client.download(
                remotePath = normalizedPath,
                localDestination = verification,
                operation = OperationContext.background(),
                maxBytes = EditLimits.MAX_FILE_BYTES,
            )
            withContext(Dispatchers.IO) {
                sessions.revisionFromStream(remoteEntry.modifiedAtMillis, verification::inputStream)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { sessions.discardVerification(verification) }
        }
    }
}
