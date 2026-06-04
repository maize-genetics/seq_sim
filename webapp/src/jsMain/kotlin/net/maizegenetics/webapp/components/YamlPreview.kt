package net.maizegenetics.webapp.components

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.pre
import web.cssom.ClassName

external interface YamlPreviewProps : Props {
    var yaml: String
    var fileName: String
}

/** Live YAML preview with copy-to-clipboard and download actions. */
val YamlPreview = FC<YamlPreviewProps> { props ->
    div {
        className = ClassName("card preview-card")

        div {
            className = ClassName("preview-head")
            h2 {
                className = ClassName("preview-title")
                +"YAML preview"
            }
            div {
                className = ClassName("preview-actions")
                button {
                    className = ClassName("btn secondary")
                    onClick = { copyToClipboard(props.yaml) }
                    +"Copy"
                }
                button {
                    className = ClassName("btn primary")
                    onClick = { downloadFile(props.fileName, props.yaml) }
                    +"Download"
                }
            }
        }

        pre {
            className = ClassName("yaml-pre")
            code { +props.yaml }
        }
    }
}

/** Copies [text] to the clipboard via the browser Clipboard API. */
private fun copyToClipboard(text: String) {
    val navigator: dynamic = js("navigator")
    val clipboard = navigator.clipboard
    if (clipboard != null && clipboard != undefined) {
        clipboard.writeText(text)
    }
}

/** Triggers a browser download of [content] as [fileName] using a data URI. */
private fun downloadFile(fileName: String, content: String) {
    val document: dynamic = js("document")
    val anchor = document.createElement("a")
    anchor.href = "data:text/yaml;charset=utf-8," + encodeURIComponent(content)
    anchor.setAttribute("download", fileName)
    document.body.appendChild(anchor)
    anchor.click()
    document.body.removeChild(anchor)
}

private external fun encodeURIComponent(value: String): String
