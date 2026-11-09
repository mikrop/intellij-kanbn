package com.github.mikrop.intellijkanbn.parse

import com.github.mikrop.intellijkanbn.model.KanbnBoard
import com.github.mikrop.intellijkanbn.model.KanbnTask
import com.github.mikrop.intellijkanbn.model.KanbnWorkspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/** Nacitani kanbn workspace z disku. Vse jen pro cteni, nic se nezapisuje. */
object KanbnLoader {

    private const val DEFAULT_FOLDER = ".kanbn"
    private const val DEFAULT_INDEX = "index.md"
    private const val DEFAULT_TASKS = "tasks"

    fun findWorkspace(root: Path): KanbnWorkspace? {
        val config = readConfig(root)
        val mainFolder = root.resolve(config["mainFolder"] as? String ?: DEFAULT_FOLDER).normalize()
        val indexFileName = config["indexFile"] as? String ?: DEFAULT_INDEX
        if (!mainFolder.resolve(indexFileName).isRegularFile()) return null
        val taskFolder = mainFolder.resolve(config["taskFolder"] as? String ?: DEFAULT_TASKS)
        return KanbnWorkspace(root, mainFolder, indexFileName, taskFolder, config)
    }

    private fun readConfig(root: Path): Map<String, Any?> {
        for (name in listOf("kanbn.yml", "kanbn.json")) {
            val file = root.resolve(name)
            if (file.isRegularFile()) return KanbnParser.yamlMap(file.readText())
        }
        return emptyMap()
    }

    fun mainSlug(ws: KanbnWorkspace): String = ws.indexFileName.removeSuffix(".md")

    /** Boardy = *.md primo ve slozce kanbn, ktere maji H1 (docs/multiple-boards.md, Board discovery). */
    fun listBoards(ws: KanbnWorkspace): List<KanbnBoard> {
        val boardsConfig = ws.config["boards"] as? Map<*, *>
        val exclude = (boardsConfig?.get("exclude") as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet()
        val order = (boardsConfig?.get("order") as? List<*>)?.map { it.toString() } ?: emptyList()
        val main = mainSlug(ws)

        val files = Files.list(ws.mainFolder).use { s ->
            s.filter { it.isRegularFile() && it.extension == "md" && it.name !in exclude }.toList()
        }
        val boards = files.mapNotNull { loadBoard(ws, it) }
        return boards.sortedWith(
            compareBy<KanbnBoard> { if (it.slug == main) 0 else 1 }
                .thenBy { order.indexOf(it.slug).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.slug }
        )
    }

    private fun loadBoard(ws: KanbnWorkspace, file: Path): KanbnBoard? {
        val parsed = runCatching { KanbnParser.parseIndex(file.readText()) }.getOrNull() ?: return null
        val slug = file.nameWithoutExtension
        val options = LinkedHashMap<String, Any?>(ws.config)
        ((ws.config["boards"] as? Map<*, *>)?.get(slug) as? Map<*, *>)?.forEach { (k, v) -> options[k.toString()] = v }
        options.putAll(parsed.options)
        return KanbnBoard(slug, file, parsed.title, parsed.description, options, parsed.columns)
    }

    fun loadTask(ws: KanbnWorkspace, id: String): KanbnTask {
        val file = ws.taskFolder.resolve("$id.md")
        if (!file.isRegularFile()) {
            return KanbnTask(id, file, false, id, emptyMap(), "", emptyList(), emptyList(), emptyList(), emptyList())
        }
        val p = KanbnParser.parseTask(file.readText())
        return KanbnTask(id, file, true, p.name ?: id, p.metadata, p.description, p.subTasks, p.relations, p.comments, p.history)
    }
}
