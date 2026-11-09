package com.github.mikrop.intellijkanbn

import com.google.gson.GsonBuilder
import com.github.mikrop.intellijkanbn.parse.KanbnLoader
import com.github.mikrop.intellijkanbn.parse.KanbnParser
import com.github.mikrop.intellijkanbn.ui.BoardData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.collections.get
import kotlin.text.get

/** Fixture v src/test/resources/fixture vznikla kanbn CLI (fork mikrop/kanbn, vetev fix-windows-glob). */
class KanbnLoaderTest {

    private val root = Paths.get(javaClass.getResource("/fixture/.kanbn/index.md")!!.toURI()).parent.parent

    @Test
    fun readsBoardColumnsAndSimpleTasks() {
        val ws = KanbnLoader.findWorkspace(root)
        assertNotNull(ws)
        ws!!
        val boards = KanbnLoader.listBoards(ws)
        assertEquals(listOf("index"), boards.map { it.slug })
        val board = boards.single()
        assertEquals("Projekt XY", board.title)
        assertEquals(listOf("Backlog", "Analýza", "Vývoj", "Review", "Hotovo"), board.columns.map { it.name })
        assertEquals(listOf("kontrola-xy"), board.columns[3].taskIds)
        assertEquals(listOf("zkontrolovat ručně deploy"), board.columns[3].simpleTasks)
    }

    @Test
    fun readsTaskDetail() {
        val ws = KanbnLoader.findWorkspace(root)!!
        val task = KanbnLoader.loadTask(ws, "rest-api-xy")
        assertTrue(task.exists)
        assertEquals("REST API XY", task.name)
        assertEquals(0.4, (task.metadata["progress"] as Number).toDouble(), 0.0001)
        assertEquals(listOf("server", "api"), task.metadata["tags"])
        assertEquals("server-dev", task.comments.single().author)
        assertEquals("Hotov controller, chybí testy.", task.comments.single().text)
        assertTrue(task.history.any { it["type"] == "moved" && it["toColumn"] == "Review" })

        val dm = KanbnLoader.loadTask(ws, "datový-model-dokumentu")
        assertEquals(2, dm.subTasks.size)
        assertTrue(dm.description.contains("vazba na modul XY"))

        val rel = KanbnLoader.loadTask(ws, "kontrola-xy").relations.single()
        assertEquals("requires", rel.type)
        assertEquals("rest-api-xy", rel.taskId)

        assertFalse(KanbnLoader.loadTask(ws, "neexistuje").exists)
    }

    @Test
    fun parserIgnoresHeadingsInCodeBlocks() {
        val p = KanbnParser.parseIndex("# Board\n\n```\n## Not a column\n```\n\n## Todo\n\n- [a](tasks/a.md)\n")!!
        assertEquals(listOf("Todo"), p.columns.map { it.name })
        assertEquals(listOf("a"), p.columns.single().taskIds)
    }

    /** Kdyz agent presune ukol (zmeni index.md), nove nacteni boardu ukazuje ukol v novem sloupci. */
    @Test
    fun reloadShowsMovedTask() {
        val tmp = Files.createTempDirectory("kanbn-test")
        try {
            root.resolve(".kanbn").toFile().copyRecursively(tmp.resolve(".kanbn").toFile())
            moveTask(tmp.resolve(".kanbn/index.md"), "kontrola-xy", "Hotovo")

            val board = KanbnLoader.listBoards(KanbnLoader.findWorkspace(tmp)!!).single()
            assertTrue("kontrola-xy" in board.columns.first { it.name == "Hotovo" }.taskIds)
            assertTrue(board.columns.first { it.name == "Review" }.taskIds.isEmpty())
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    companion object {
        /** Presun ukolu do jineho sloupce stejne, jak ho v index.md udela `kanbn move`. */
        fun moveTask(index: Path, id: String, column: String) {
            val link = "- [$id](tasks/$id.md)\n"
            val text = Files.readString(index).replace("\r\n", "\n")
            Files.writeString(index, text.replace(link, "").replace("## $column\n", "## $column\n\n$link"))
        }
    }

    @Test
    fun buildsBoardData() {
        val data = BoardData.build(KanbnLoader.findWorkspace(root), null)
        @Suppress("UNCHECKED_CAST")
        val tasks = data["tasks"] as Map<String, Map<String, Any?>>
        assertEquals(true, tasks["ci-pipeline"]!!["completed"])
        assertEquals(true, tasks["rest-api-xy"]!!["started"])
        assertEquals(true, tasks["obrazovka-seznamu"]!!["overdue"])
        // JSON pro rucni kontrolu stranky v prohlizeci
        val out = Paths.get("build", "board-data.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create().toJson(data))
    }
}
