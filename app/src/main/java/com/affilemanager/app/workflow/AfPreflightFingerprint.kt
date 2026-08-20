package com.affilemanager.app.workflow

import java.security.MessageDigest

object AfPreflightFingerprint {
    fun create(preflight: AfPreflightSummary): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0)
            digest.update(bytes)
            digest.update(0)
        }
        val plan = preflight.plan
        add(plan.id)
        add(plan.conflictPolicy.name)
        add(plan.verification.name)
        add(plan.failurePolicy.name)
        add(plan.deleteSourcesAfterVerifiedCopies.toString())
        plan.sources.forEach { source ->
            add(source.kind.name)
            add(source.location.identityKey())
            add(source.archiveEntryPath.orEmpty())
        }
        plan.destinations.forEach { destination ->
            add(destination.location.identityKey())
            add(destination.required.toString())
        }
        preflight.entries.forEach { entry ->
            add(entry.sourceRootIndex.toString())
            add(entry.relativePath)
            add(entry.source.directory.toString())
            add(entry.source.sizeBytes.toString())
            add(entry.source.modifiedAtMillis?.toString().orEmpty())
            add(entry.source.sha256.orEmpty())
        }
        preflight.projections.forEach { projection ->
            add(projection.destinationIndex.toString())
            add(projection.sourceRootIndex.toString())
            add(projection.targetRoot.identityKey())
            add(projection.disposition.name)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
