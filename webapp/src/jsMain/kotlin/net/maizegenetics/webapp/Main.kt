package net.maizegenetics.webapp

import net.maizegenetics.webapp.components.App
import react.create
import react.dom.client.createRoot
import web.dom.Element

fun main() {
    val container = js("document.getElementById('root')").unsafeCast<Element>()
    createRoot(container).render(App.create())
}
