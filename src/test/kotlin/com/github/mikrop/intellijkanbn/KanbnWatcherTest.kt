package com.github.mikrop.intellijkanbn

import com.github.mikrop.intellijkanbn.watch.KanbnWatcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Watcher musi hlasit zmeny, ktere udelaji agenti pres kanbn CLI, a ignorovat ostatni soubory projektu. */
class KanbnWatcherTest {

    private val fixture = Paths.get(javaClass.getResource("/fixture/.kanbn/index.md")!!.toURI()).parent
    private lateinit var root: Path
    private val changes = Semaphore(0)
    private var watcher: KanbnWatcher? = null

    @Before
    fun setUp() {
        root = Files.createTempDirectory("kanbn-watch")
    }

    @After
    fun tearDown() {
        watcher?.close()
        root.toFile().deleteRecursively()
    }

    private fun startWatcher() {
        watcher = KanbnWatcher(root) { changes.release() }.start()
    }

    /** Pocka na zmenu a pak vyprazdni dalsi udalosti stejne davky. */
    private fun awaitChange(): Boolean {
        val got = changes.tryAcquire(5, TimeUnit.SECONDS)
        Thread.sleep(300)
        changes.drainPermits()
        return got
    }

    private fun copyFixture() = fixture.toFile().copyRecursively(root.resolve(".kanbn").toFile())

    @Test
    fun movingTaskNotifies() {
        copyFixture()
        startWatcher()
        KanbnLoaderTest.moveTask(root.resolve(".kanbn/index.md"), "kontrola-xy", "Hotovo")
        assertTrue(awaitChange())
    }

    @Test
    fun editingTaskFileNotifies() {
        copyFixture()
        startWatcher()
        val task = root.resolve(".kanbn/tasks/ci-pipeline.md")
        Files.writeString(task, Files.readString(task) + "\n")
        assertTrue(awaitChange())
    }

    @Test
    fun otherProjectFilesAreIgnored() {
        copyFixture()
        startWatcher()
        Files.writeString(root.resolve("Main.kt"), "fun main() {}")
        Files.createDirectories(root.resolve("src")).resolve("A.kt").let { Files.writeString(it, "") }
        assertFalse(changes.tryAcquire(1, TimeUnit.SECONDS))
    }

    @Test
    fun boardCreatedAfterStartIsWatched() {
        startWatcher()
        val kanbn = Files.createDirectories(root.resolve(".kanbn/tasks"))
        Files.writeString(kanbn.parent.resolve("index.md"), "# Board\n\n## Todo\n")
        assertTrue(awaitChange())

        // Po vzniku slozky se zacne sledovat i slozka ukolu
        val deadline = System.currentTimeMillis() + 5000
        while (kanbn !in watcher!!.watchedFolders() && System.currentTimeMillis() < deadline) {
            changes.tryAcquire(100, TimeUnit.MILLISECONDS)
        }
        assertTrue(kanbn in watcher!!.watchedFolders())
        changes.drainPermits()
        Files.writeString(kanbn.resolve("novy-ukol.md"), "# Novy ukol\n")
        assertTrue(awaitChange())
    }
}
