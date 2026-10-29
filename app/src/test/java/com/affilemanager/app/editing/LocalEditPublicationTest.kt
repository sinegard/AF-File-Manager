package com.affilemanager.app.editing

import com.affilemanager.app.advanced.PrivilegedFileMetadata
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Publication/failure ordering only; real owner/mode/label assertions run on Android. */
class LocalEditPublicationTest {
    @get:Rule val temporary=TemporaryFolder()

    @Test fun preservationFailureKeepsOriginalDraftAndNoTemporaryCopy() {
        var closes=0
        val files=object : LocalEditFiles by HostLocalEditFiles {
            override fun openStage(file: File): LocalEditStage {
                val stage=HostLocalEditFiles.openStage(file)
                return object : LocalEditStage by stage {
                    override fun prepare(original: PrivilegedFileMetadata?): PrivilegedFileMetadata {
                        stage.prepare(original)
                        throw IllegalStateException(LOCAL_EDIT_ACCESS_ERROR)
                    }
                    override fun close() { closes++; stage.close() }
                }
            }
        }
        fixture(files) { store,session,source ->
            repeat(12) {
                assertThrows(IllegalStateException::class.java) { store.saveLocal(session,false) }
                assertEquals("original",source.readText())
                assertEquals("edited",session.workingFile.readText())
                assertEquals(setOf(source.name),checkNotNull(source.parentFile).list()!!.toSet())
            }
        }
        assertEquals(12,closes)
    }

    @Test fun aSecurityChangeDuringWritingIsNotOverwrittenEvenWithForce() {
        for(force in listOf(false,true)) {
            var sourcePath=""
            var reads=0
            val files=object : LocalEditFiles by HostLocalEditFiles {
                override fun metadata(file: File): PrivilegedFileMetadata? {
                    val result=HostLocalEditFiles.metadata(file)
                    return if(file.canonicalPath==sourcePath && ++reads>=2) result?.copy(mode=0x81a0) else result
                }
            }
            fixture(files) { store,session,source ->
                sourcePath=source.canonicalPath
                assertThrows(IllegalStateException::class.java) { store.saveLocal(session,force) }
                assertEquals("original",source.readText())
                assertEquals("edited",session.workingFile.readText())
                assertEquals(setOf(source.name),checkNotNull(source.parentFile).list()!!.toSet())
            }
        }
    }

    @Test fun aStageFailureDoesNotConsumeTheOriginalOrEditorBase() {
        val files=object : LocalEditFiles by HostLocalEditFiles {
            override fun openStage(file: File): LocalEditStage { throw IOException("controlled stage failure") }
        }
        fixture(files) { store,session,source ->
            assertThrows(IOException::class.java) { store.saveLocal(session,false) }
            assertEquals("original",source.readText())
            assertEquals("original",store.readBaseTextDocument(session).text)
            assertEquals("edited",session.workingFile.readText())
        }
    }

    @Test fun aContentChangeAfterMetadataPreparationStillUsesTheExistingConflict() {
        var changed:File?=null
        val files=object : LocalEditFiles by HostLocalEditFiles {
            override fun openStage(file: File): LocalEditStage {
                val stage=HostLocalEditFiles.openStage(file)
                return object : LocalEditStage by stage {
                    override fun prepare(original: PrivilegedFileMetadata?): PrivilegedFileMetadata {
                        val result=stage.prepare(original)
                        checkNotNull(changed).writeText("external change")
                        return result
                    }
                }
            }
        }
        fixture(files) { store,session,source ->
            changed=source
            assertTrue(store.saveLocal(session,false) is EditSaveResult.Conflict)
            assertEquals("external change",source.readText())
            assertEquals("edited",session.workingFile.readText())
            assertEquals(setOf(source.name),checkNotNull(source.parentFile).list()!!.toSet())
        }
    }

    private fun fixture(files: LocalEditFiles,block:(EditSessionStore,EditSession,File)->Unit) {
        val root=temporary.newFolder()
        val source=File(root,"original.txt").apply { writeText("original") }
        val store=EditSessionStore(temporary.newFolder(),files)
        val initial=store.prepareFromFile(source.path,source.name,"text/plain",source,
            EditOrigin.Local(source.path,true),source.lastModified(),true)
        val session=store.stageText(initial,"edited")
        block(store,session,source)
    }
}
