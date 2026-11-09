package com.github.mikrop.intellijkanbn.parse

import com.github.mikrop.intellijkanbn.model.Comment
import com.github.mikrop.intellijkanbn.model.KanbnColumn
import com.github.mikrop.intellijkanbn.model.Relation
import com.github.mikrop.intellijkanbn.model.SubTask
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.Date

/**
 * Cteni markdown souboru kanbn (board i ukol) podle docs/index-structure.md a docs/task-structure.md
 * z kanbn 2.6. Parser je jen pro cteni a je tolerantni: co nepozna, preskoci.
 */
object KanbnParser {

    private val TASK_LINK = Regex("""^[-*+]\s+\[([^\]]+)]\(([^)]+)\)""")
    private val LIST_ITEM = Regex("""^[-*+]\s+(.*)$""")
    private val SUB_TASK = Regex("""^[-*+]\s+\[([ xX])]\s+(.*)$""")
    private val FENCE = Regex("""^\s*(```|~~~)""")

    private val TASK_SECTIONS = setOf("metadata", "sub-tasks", "relations", "comments", "history")

    data class Document(
        val frontMatter: Map<String, Any?>,
        val title: String?,
        /** Text pred prvni H2 (bez H1). */
        val preamble: List<String>,
        /** H2 sekce v poradi souboru. */
        val sections: List<Pair<String, List<String>>>,
    )

    data class ParsedIndex(
        val title: String,
        val description: String,
        val options: Map<String, Any?>,
        val columns: List<KanbnColumn>,
    )

    data class ParsedTask(
        val name: String?,
        val metadata: Map<String, Any?>,
        val description: String,
        val subTasks: List<SubTask>,
        val relations: List<Relation>,
        val comments: List<Comment>,
        val history: List<Map<String, Any?>>,
    )

    fun parseDocument(text: String): Document {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var start = 0
        var frontMatter: Map<String, Any?> = emptyMap()
        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            if (end != null) {
                frontMatter = yamlMap(lines.subList(1, end).joinToString("\n"))
                start = end + 1
            }
        }

        var title: String? = null
        val preamble = mutableListOf<String>()
        val sections = mutableListOf<Pair<String, MutableList<String>>>()
        var inFence = false
        for (i in start until lines.size) {
            val line = lines[i]
            if (FENCE.containsMatchIn(line)) inFence = !inFence
            if (!inFence && line.startsWith("## ")) {
                sections += line.removePrefix("## ").trim() to mutableListOf()
                continue
            }
            if (!inFence && title == null && sections.isEmpty() && line.startsWith("# ")) {
                title = line.removePrefix("# ").trim()
                continue
            }
            if (sections.isEmpty()) preamble += line else sections.last().second += line
        }
        return Document(frontMatter, title, preamble, sections)
    }

    fun parseIndex(text: String): ParsedIndex? {
        val doc = parseDocument(text)
        val title = doc.title ?: return null
        val options = LinkedHashMap(doc.frontMatter)
        val columns = mutableListOf<KanbnColumn>()
        for ((name, body) in doc.sections) {
            if (name.equals("Options", ignoreCase = true)) {
                options.putAll(yamlMap(codeBlock(body)))
                continue
            }
            val ids = mutableListOf<String>()
            val simple = mutableListOf<String>()
            var inFence = false
            for (line in body) {
                if (FENCE.containsMatchIn(line)) inFence = !inFence
                if (inFence) continue
                val link = TASK_LINK.find(line)
                if (link != null) {
                    ids += link.groupValues[1].trim()
                    continue
                }
                LIST_ITEM.find(line)?.let { simple += it.groupValues[1].replace(Regex("""^\[[ xX]]\s+"""), "").trim() }
            }
            columns += KanbnColumn(name, ids, simple)
        }
        return ParsedIndex(title, doc.preamble.joinToString("\n").trim(), options, columns)
    }

    fun parseTask(text: String): ParsedTask {
        val doc = parseDocument(text)
        val metadata = LinkedHashMap(doc.frontMatter)
        val description = StringBuilder(doc.preamble.joinToString("\n"))
        val subTasks = mutableListOf<SubTask>()
        val relations = mutableListOf<Relation>()
        var comments = emptyList<Comment>()
        var history = emptyList<Map<String, Any?>>()

        for ((name, body) in doc.sections) {
            when (name.lowercase()) {
                "metadata" -> metadata.putAll(yamlMap(codeBlock(body)))
                "sub-tasks" -> body.mapNotNullTo(subTasks) { line ->
                    SUB_TASK.find(line)?.let { SubTask(it.groupValues[2].trim(), it.groupValues[1] != " ") }
                        ?: LIST_ITEM.find(line)?.let { SubTask(it.groupValues[1].trim(), false) }
                }
                "relations" -> body.mapNotNullTo(relations) { line ->
                    TASK_LINK.find(line)?.let { m ->
                        val id = m.groupValues[2].substringAfterLast('/').removeSuffix(".md")
                        val type = m.groupValues[1].trim().removeSuffix(id).trim()
                        Relation(type, id)
                    }
                }
                "comments" -> comments = parseComments(body)
                "history" -> history = yamlList(body.joinToString("\n"))
                else -> if (name.lowercase() !in TASK_SECTIONS) {
                    description.append("\n\n## ").append(name).append('\n').append(body.joinToString("\n"))
                }
            }
        }
        return ParsedTask(
            doc.title,
            normalize(metadata),
            description.toString().trim(),
            subTasks,
            relations,
            comments,
            history.map { normalize(it) },
        )
    }

    private fun parseComments(body: List<String>): List<Comment> {
        val items = mutableListOf<MutableList<String>>()
        for (line in body) {
            when {
                line.startsWith("- ") -> items += mutableListOf(line.removePrefix("- "))
                items.isNotEmpty() -> items.last() += line.removePrefix("  ")
            }
        }
        return items.map { item ->
            var author: String? = null
            var date: String? = null
            var i = 0
            while (i < item.size) {
                val l = item[i].trim()
                when {
                    author == null && l.startsWith("author:") -> author = l.removePrefix("author:").trim()
                    date == null && l.startsWith("date:") -> date = l.removePrefix("date:").trim()
                    else -> break
                }
                i++
            }
            Comment(author, date, item.drop(i).joinToString("\n").trim())
        }
    }

    /** Obsah prvniho code blocku v sekci (Options, Metadata). */
    private fun codeBlock(body: List<String>): String {
        val start = body.indexOfFirst { FENCE.containsMatchIn(it) }
        if (start < 0) return body.joinToString("\n")
        val end = (start + 1 until body.size).firstOrNull { FENCE.containsMatchIn(body[it]) } ?: body.size
        return body.subList(start + 1, end).joinToString("\n")
    }

    private fun yaml() = Yaml(SafeConstructor(LoaderOptions()))

    @Suppress("UNCHECKED_CAST")
    fun yamlMap(text: String): Map<String, Any?> =
        runCatching { yaml().load<Any?>(text) as? Map<String, Any?> }.getOrNull() ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun yamlList(text: String): List<Map<String, Any?>> =
        runCatching { (yaml().load<Any?>(text) as? List<Any?>)?.filterIsInstance<Map<String, Any?>>() }
            .getOrNull() ?: emptyList()

    /** Datumy z YAML na ISO retezce, aby sly do JSON a zobrazily se jednotne. */
    private fun normalize(map: Map<String, Any?>): Map<String, Any?> = map.mapValues { (_, v) -> normalizeValue(v) }

    private fun normalizeValue(v: Any?): Any? = when (v) {
        is Date -> v.toInstant().toString()
        is Map<*, *> -> v.entries.associate { it.key.toString() to normalizeValue(it.value) }
        is List<*> -> v.map { normalizeValue(it) }
        else -> v
    }
}
