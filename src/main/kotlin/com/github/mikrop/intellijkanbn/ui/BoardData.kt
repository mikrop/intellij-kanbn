package com.github.mikrop.intellijkanbn.ui

import com.github.mikrop.intellijkanbn.model.KanbnBoard
import com.github.mikrop.intellijkanbn.model.KanbnTask
import com.github.mikrop.intellijkanbn.model.KanbnWorkspace
import com.github.mikrop.intellijkanbn.parse.KanbnLoader
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import java.time.Instant

/** Data pro stranku boardu. Serializuje se do JSON a predava do JS. */
object BoardData {

    private val flavour = GFMFlavourDescriptor()

    /** Nacte workspace a vybrany board. Vraci mapu pripravenou pro JSON. */
    fun build(ws: KanbnWorkspace?, requestedSlug: String?): Map<String, Any?> {
        if (ws == null) {
            return mapOf("error" to "noWorkspace")
        }
        val boards = KanbnLoader.listBoards(ws)
        if (boards.isEmpty()) return mapOf("error" to "noBoard")
        val board = boards.firstOrNull { it.slug == requestedSlug } ?: boards.first()

        val hidden = stringList(board.options["hiddenColumns"]).toSet()
        val startedField = board.options["startedField"] as? String ?: "started"
        val completedField = board.options["completedField"] as? String ?: "completed"

        val tasks = LinkedHashMap<String, KanbnTask>()
        val columnOf = HashMap<String, String>()
        for (column in board.columns) {
            for (id in column.taskIds) {
                tasks.getOrPut(id) { KanbnLoader.loadTask(ws, id) }
                columnOf[id] = column.name
            }
        }
        // Ukoly, na ktere odkazuji relace, aby sel otevrit i jejich detail
        for (task in tasks.values.toList()) {
            for (rel in task.relations) {
                if (rel.taskId !in tasks) {
                    val t = KanbnLoader.loadTask(ws, rel.taskId)
                    if (t.exists) tasks[rel.taskId] = t
                }
            }
        }

        val now = Instant.now()
        val taskDtos = tasks.mapValues { (_, t) -> taskDto(t, columnOf[t.id], startedField, completedField, now) }

        return mapOf(
            "boards" to boards.map { mapOf("slug" to it.slug, "title" to it.title) },
            "board" to board.slug,
            "title" to board.title,
            "descriptionHtml" to markdown(board.description),
            "columns" to board.columns.filter { it.name !in hidden }.map { c ->
                mapOf("name" to c.name, "taskIds" to c.taskIds, "simple" to c.simpleTasks)
            },
            "hiddenColumns" to board.columns.filter { it.name in hidden }.map { mapOf("name" to it.name, "count" to it.taskIds.size) },
            "tasks" to taskDtos,
        )
    }

    private fun taskDto(t: KanbnTask, column: String?, startedField: String, completedField: String, now: Instant): Map<String, Any?> {
        val m = t.metadata
        val completed = m[completedField] != null
        val started = m[startedField] != null
        val progress = if (completed) 1.0 else (m["progress"] as? Number)?.toDouble()
        val due = m["due"]?.toString()
        val overdue = !completed && due != null && runCatching { Instant.parse(due).isBefore(now) }.getOrDefault(false)
        return mapOf(
            "id" to t.id,
            "exists" to t.exists,
            "file" to t.file.toString(),
            "name" to t.name,
            "column" to column,
            "assigned" to m["assigned"]?.toString(),
            "tags" to stringList(m["tags"]),
            "progress" to progress,
            "started" to started,
            "completed" to completed,
            "due" to due,
            "overdue" to overdue,
            "created" to m["created"]?.toString(),
            "updated" to m["updated"]?.toString(),
            "subTasks" to t.subTasks.map { mapOf("text" to it.text, "completed" to it.completed) },
            "relations" to t.relations.map { mapOf("type" to it.type, "id" to it.taskId) },
            "comments" to t.comments.map { mapOf("author" to it.author, "date" to it.date, "html" to markdown(it.text)) },
            "history" to t.history,
            "metadata" to m.map { (k, v) -> listOf(k, display(v)) },
            "descriptionHtml" to markdown(t.description),
        )
    }

    private fun stringList(v: Any?): List<String> = when (v) {
        is List<*> -> v.filterNotNull().map { it.toString() }
        null -> emptyList()
        else -> listOf(v.toString())
    }

    private fun display(v: Any?): String = when (v) {
        null -> ""
        is List<*> -> v.joinToString(", ") { display(it) }
        is Map<*, *> -> v.entries.joinToString(", ") { "${it.key}: ${display(it.value)}" }
        else -> v.toString()
    }

    fun markdown(src: String): String {
        if (src.isBlank()) return ""
        val text: CharSequence = src
        val tree = MarkdownParser(flavour, true, CancellationToken.NonCancellable).buildMarkdownTreeFromString(text)
        return HtmlGenerator(src, tree, flavour).generateHtml().removePrefix("<body>").removeSuffix("</body>")
    }
}
