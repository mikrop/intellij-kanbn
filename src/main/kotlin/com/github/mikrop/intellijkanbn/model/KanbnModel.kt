package com.github.mikrop.intellijkanbn.model

import java.nio.file.Path

/** Umisteni kanbn workspace v projektu (slozka a nazvy souboru podle kanbn.yml / kanbn.json). */
data class KanbnWorkspace(
    val root: Path,
    val mainFolder: Path,
    val indexFileName: String,
    val taskFolder: Path,
    val config: Map<String, Any?>,
)

/** Board nacteny z markdown souboru primo ve slozce kanbn (index.md je hlavni board). */
data class KanbnBoard(
    val slug: String,
    val file: Path,
    val title: String,
    val description: String,
    val options: Map<String, Any?>,
    val columns: List<KanbnColumn>,
)

data class KanbnColumn(
    val name: String,
    val taskIds: List<String>,
    /** Radky ve sloupci, ktere nejsou odkazem na ukol (kanbn je zobrazuje ztlumene). */
    val simpleTasks: List<String>,
)

data class KanbnTask(
    val id: String,
    val file: Path,
    /** False, pokud board odkazuje na ukol, jehoz soubor neexistuje. */
    val exists: Boolean,
    val name: String,
    val metadata: Map<String, Any?>,
    val description: String,
    val subTasks: List<SubTask>,
    val relations: List<Relation>,
    val comments: List<Comment>,
    val history: List<Map<String, Any?>>,
)

data class SubTask(val text: String, val completed: Boolean)

data class Relation(val type: String, val taskId: String)

data class Comment(val author: String?, val date: String?, val text: String)
