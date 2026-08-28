package dev.vynkor.agent.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.vynkor.agent.DrawerItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProjectFilesStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val profileId = "prof-1"
    private val projectId = "proj-1"

    @Before
    fun setUp() {
        java.io.File(context.filesDir, "project_files").deleteRecursively()
        context.getSharedPreferences("vynkor_project_files", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun addFile(name: String, content: String, mime: String = "text/plain"): ProjectFilesStore.ProjectFile? {
        val src = java.io.File(context.cacheDir, name).apply { writeText(content) }
        return ProjectFilesStore.add(context, profileId, projectId, android.net.Uri.fromFile(src), name, mime)
    }

    @Test
    fun addListRemoveRoundTrip() {
        val added = addFile("spec.md", "# Spec") ?: error("add failed")
        assertEquals(listOf(added.id), ProjectFilesStore.list(context, profileId, projectId).map { it.id })

        assertTrue(ProjectFilesStore.fileFor(context, profileId, projectId, added).exists())

        ProjectFilesStore.remove(context, profileId, projectId, added.id)
        assertTrue(ProjectFilesStore.list(context, profileId, projectId).isEmpty())
        assertFalse(ProjectFilesStore.fileFor(context, profileId, projectId, added).exists())
    }

    @Test
    fun textFilesInlineIntoContextSourcesBinaryDoNot() {
        addFile("notes.md", "project notes body")
        addFile("clip.mp4", "\u0000\u0001\u0002", mime = "video/mp4")

        val sources = ProjectFilesStore.contextSources(context, profileId, projectId)
        assertEquals(2, sources.size)
        assertEquals("project notes body", sources.first { it.name == "notes.md" }.textContent)
        assertNull(sources.first { it.name == "clip.mp4" }.textContent)
        assertEquals("video", sources.first { it.name == "clip.mp4" }.typeLabel)
    }

    @Test
    fun otherProjectsAreIsolated() {
        addFile("a.txt", "content a")
        assertEquals(
            emptyList<AiContext.Source>(),
            ProjectFilesStore.contextSources(context, profileId, "proj-other"),
        )
        assertEquals(1, ProjectFilesStore.contextSources(context, profileId, projectId).size)
    }

    @Test
    fun nullOrBlankProjectYieldsNoSources() {
        assertEquals(
            emptyList<AiContext.Source>(),
            ProjectFilesStore.contextSources(context, profileId, null),
        )
    }

    @Test
    fun deleteProjectDirClearsEverything() {
        addFile("keep.txt", "kept?")
        assertNotNull(ProjectFilesStore.list(context, profileId, projectId).firstOrNull())
        ProjectFilesStore.deleteProjectDir(context, profileId, projectId)
        assertTrue(ProjectFilesStore.list(context, profileId, projectId).isEmpty())
    }

    @Test
    fun bucketDayClassifiesTodayYesterdayOlder() {
        fun at(dayOffset: Int, hour: Int): Long =
            java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

        assertEquals(0, DrawerItem.bucketDay(at(0, 12)))
        assertEquals(1, DrawerItem.bucketDay(at(-1, 12)))
        assertEquals(2, DrawerItem.bucketDay(at(-5, 12)))
    }
}
