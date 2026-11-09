package com.github.mikrop.intellijkanbn.watch

import com.github.mikrop.intellijkanbn.parse.KanbnLoader
import java.io.Closeable
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Sleduje soubory kanbn workspace pres WatchService (notifikace OS) a pri zmene vola [onChange].
 *
 * Sleduje se:
 * - koren projektu: jen kanbn.yml / kanbn.json a vznik nebo smazani kanbn slozky,
 * - kanbn slozka (boardy), slozka ukolu a archiv.
 *
 * WatchService neni rekurzivni, proto se po kazde zmene znovu urci, ktere slozky sledovat
 * (slozka mohla vzniknout, nebo konfigurace zmenila jeji nazev).
 * Vice udalosti tesne po sobe (agent zapisuje index i soubor ukolu) slouci volajici debouncem.
 */
class KanbnWatcher(private val root: Path, private val onChange: () -> Unit) : Closeable {

    private val service: WatchService = root.fileSystem.newWatchService()
    private val keys = HashMap<Path, WatchKey>()
    private val thread = Thread(::run, "kanbn-watcher").apply { isDaemon = true }

    @Volatile private var kanbnFolderName: String = DEFAULT_FOLDER

    fun start(): KanbnWatcher {
        updateWatchedFolders()
        thread.start()
        return this
    }

    private fun run() {
        try {
            while (true) {
                val key = service.take()
                val dir = key.watchable() as Path
                var relevant = false
                for (event in key.pollEvents()) {
                    relevant = relevant || event.kind() == OVERFLOW ||
                        isRelevant(dir, event.kind(), event.context() as? Path)
                }
                if (!key.reset()) {
                    synchronized(keys) { keys.remove(dir) }
                }
                if (relevant) {
                    updateWatchedFolders()
                    onChange()
                }
            }
        } catch (_: ClosedWatchServiceException) {
        } catch (_: InterruptedException) {
        }
    }

    /**
     * V koreni projektu je zajimava jen konfigurace a samotna kanbn slozka, ostatni zmeny (kod) se ignoruji.
     * ENTRY_MODIFY na podslozce je jen zmena metadat adresare (Windows ji posila i pri cteni), obsah
     * sledovanych podslozek hlasi jejich vlastni klic.
     */
    private fun isRelevant(dir: Path, kind: WatchEvent.Kind<*>, name: Path?): Boolean {
        if (name == null) return true
        if (kind == ENTRY_MODIFY && dir.resolve(name).isDirectory()) return false
        if (dir != root) return true
        val n = name.name
        return n == "kanbn.yml" || n == "kanbn.json" || n == kanbnFolderName
    }

    private fun updateWatchedFolders() {
        val ws = runCatching { KanbnLoader.findWorkspace(root) }.getOrNull()
        val mainFolder = ws?.mainFolder ?: root.resolve(DEFAULT_FOLDER)
        kanbnFolderName = if (mainFolder.parent == root) mainFolder.name else DEFAULT_FOLDER

        val wanted = linkedSetOf(root, mainFolder)
        ws?.let {
            wanted.add(it.taskFolder)
            wanted.add(it.mainFolder.resolve(it.config["archiveFolder"] as? String ?: DEFAULT_ARCHIVE))
        }
        // Kanbn slozka mimo koren projektu (mainFolder v konfiguraci): sledovat i jeji rodice, aby se zachytil jeji vznik
        if (mainFolder.parent != root) mainFolder.parent?.let { wanted.add(it) }

        synchronized(keys) {
            (keys.keys - wanted).forEach { keys.remove(it)?.cancel() }
            for (dir in wanted) {
                if (dir !in keys && dir.isDirectory()) {
                    runCatching { dir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY) }
                        .onSuccess { keys[dir] = it }
                }
            }
        }
    }

    /** Sledovane slozky, pro testy. */
    fun watchedFolders(): Set<Path> = synchronized(keys) { keys.keys.toSet() }

    override fun close() {
        runCatching { service.close() }
        thread.interrupt()
    }

    private companion object {
        const val DEFAULT_FOLDER = ".kanbn"
        const val DEFAULT_ARCHIVE = "archive"
    }
}
