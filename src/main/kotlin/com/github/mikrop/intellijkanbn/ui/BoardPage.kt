package com.github.mikrop.intellijkanbn.ui

/** Sestavi HTML stranku boardu ze sablony v resources. */
object BoardPage {

    fun html(nonce: String, sendJs: String, themeCss: String): String {
        val template = BoardPage::class.java.getResource("/intellijkanbn/board.html")!!.readText()
        return template
            .replace("__NONCE__", nonce)
            .replace("__THEME__", themeCss)
            .replace("__SEND__", sendJs)
    }
}
