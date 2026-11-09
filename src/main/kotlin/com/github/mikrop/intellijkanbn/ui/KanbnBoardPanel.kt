package com.github.mikrop.intellijkanbn.ui

import com.google.gson.GsonBuilder
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.github.mikrop.intellijkanbn.parse.KanbnLoader
import com.github.mikrop.intellijkanbn.watch.KanbnWatcher
import java.awt.BorderLayout
import java.awt.Color
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import javax.swing.JPanel

/**
 * Board kanbn v JCEF prohlizeci. Jen pro cteni: zobrazuje sloupce a karty, po kliknuti detail ukolu.
 * Stav se obnovuje sam, kdyz se zmeni soubory v kanbn slozce (zapisuji je agenti mimo IDE).
 */
class KanbnBoardPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val root: Path? = project.basePath?.let { Paths.get(it) }
    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    @Volatile private var pageReady = false
    @Volatile private var selectedBoard: String? = null

    init {
        Disposer.register(this, browser)
        Disposer.register(this, query)
        query.addHandler { request: String ->
            // Handler bezi ve vlakne JCEF, cteni souboru patri do poolu
            ApplicationManager.getApplication().executeOnPooledThread { handle(request) }
            null
        }
        add(browser.component, BorderLayout.CENTER)

        val nonce = UUID.randomUUID().toString().replace("-", "")
        browser.loadHTML(BoardPage.html(nonce, query.inject("msg"), themeCss()))

        val bus = ApplicationManager.getApplication().messageBus.connect(this)
        bus.subscribe(LafManagerListener.TOPIC, LafManagerListener { pushTheme() })
        bus.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { pushTheme() })

        // Zmeny souboru (zapisuji je agenti mimo IDE) hlasi OS pres WatchService. Udalosti tesne po sobe
        // (index + soubor ukolu) se slouci a board se nacte jednou.
        root?.let { r ->
            val watcher = KanbnWatcher(r) {
                if (!alarm.isDisposed) {
                    alarm.cancelAllRequests()
                    alarm.addRequest(::reload, DEBOUNCE_MS)
                }
            }.start()
            Disposer.register(this) { watcher.close() }
        }
    }

    private fun handle(request: String) {
        val cmd = request.substringBefore(':')
        val arg = request.substringAfter(':', "")
        when (cmd) {
            "ready" -> {
                pageReady = true
                reload()
            }
            "board" -> {
                selectedBoard = arg
                reload()
            }
        }
    }

    /** Nacte data z disku a posle je strance. */
    @Synchronized
    private fun reload() {
        val root = root ?: return
        if (!pageReady) return

        val data = try {
            BoardData.build(KanbnLoader.findWorkspace(root), selectedBoard)
        } catch (e: Exception) {
            mapOf("error" to "exception", "message" to (e.message ?: e.toString()))
        }
        (data["board"] as? String)?.let { selectedBoard = it }
        execute("window.kanbn.render(${gson.toJson(data)})")
    }

    private fun pushTheme() {
        if (!pageReady) return
        execute("window.kanbn.setTheme(${gson.toJson(themeCss())})")
    }

    private fun execute(js: String) {
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    /** CSS promenne z aktualniho vzhledu IDE, aby board ladil se svetlym i tmavym tematem. */
    private fun themeCss(): String {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val font = UIUtil.getLabelFont()
        val vars = linkedMapOf(
            "bg" to UIUtil.getPanelBackground(),
            "fg" to UIUtil.getLabelForeground(),
            "muted" to UIUtil.getContextHelpForeground(),
            "card" to scheme.defaultBackground,
            "border" to JBColor.border(),
            "accent" to JBUI.CurrentTheme.Link.Foreground.ENABLED,
            "column" to JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground()),
            "selection" to UIUtil.getListSelectionBackground(true),
            "error" to JBColor.namedColor("Label.errorForeground", JBColor(Color(0xC7222D), Color(0xFF5261))),
            "success" to JBColor(Color(0x208A3C), Color(0x5FB865)),
            "warning" to JBColor(Color(0xA46704), Color(0xD6AE58)),
        )
        val sb = StringBuilder(":root{")
        vars.forEach { (k, c) -> sb.append("--").append(k).append(':').append(hex(c)).append(';') }
        sb.append("--dark:").append(if (JBColor.isBright()) "0" else "1").append(';')
        sb.append("--font:'").append(font.family.replace("'", "")).append("',system-ui,sans-serif;")
        sb.append("--font-size:").append(JBUI.Fonts.label().size2D / JBUIScale.scale(1f)).append("px;")
        sb.append("--mono:'").append(scheme.editorFontName.replace("'", "")).append("',monospace;")
        sb.append('}')
        return sb.toString()
    }

    private fun hex(c: Color) = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    override fun dispose() {
        pageReady = false
    }

    companion object {
        private const val DEBOUNCE_MS = 200
    }
}
