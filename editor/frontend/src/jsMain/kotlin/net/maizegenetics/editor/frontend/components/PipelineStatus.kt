package net.maizegenetics.editor.frontend.components

import emotion.react.css
import kotlinx.browser.window
import net.maizegenetics.editor.frontend.M3Colors
import net.maizegenetics.editor.shared.LogMessage
import net.maizegenetics.editor.shared.PipelineStatus
import react.*
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import web.cssom.*

external interface PipelineStatusPanelProps : Props {
    var status: PipelineStatus?
    var onClose: () -> Unit
}

val PipelineStatusPanel = FC<PipelineStatusPanelProps> { props ->
    var logs by useState<List<LogMessage>>(emptyList())
    var wsConnected by useState(false)
    val logContainerRef = useRef<web.html.HTMLDivElement>(null)

    // Connect to WebSocket for log streaming
    useEffect(props.status?.running) {
        if (props.status?.running == true) {
            val protocol = if (window.location.protocol == "https:") "wss:" else "ws:"
            val wsUrl = "$protocol//${window.location.host}/api/pipeline/logs"
            
            val ws = org.w3c.dom.WebSocket(wsUrl)
            
            ws.onopen = {
                wsConnected = true
                console.log("WebSocket connected")
            }
            
            ws.onmessage = { event ->
                val data = event.data.toString()
                try {
                    val logMessage = kotlinx.serialization.json.Json.decodeFromString<LogMessage>(data)
                    logs = logs + logMessage
                    // Auto-scroll to bottom
                    logContainerRef.current?.let { container ->
                        container.scrollTop = container.scrollHeight.toDouble()
                    }
                } catch (e: Exception) {
                    console.error("Failed to parse log message", e)
                }
            }
            
            ws.onclose = {
                wsConnected = false
                console.log("WebSocket disconnected")
            }
            
            ws.onerror = { error ->
                console.error("WebSocket error", error)
            }
        }
    }

    val status = props.status ?: return@FC

    // Bottom sheet style panel
    div {
        css {
            position = Position.fixed
            bottom = 0.px
            left = 0.px
            right = 0.px
            height = 320.px
            background = M3Colors.surfaceContainerLowest
            borderTop = Border(1.px, LineStyle.solid, M3Colors.outlineVariant)
            borderTopLeftRadius = 28.px
            borderTopRightRadius = 28.px
            display = Display.flex
            flexDirection = FlexDirection.column
            zIndex = integer(1000)
            boxShadow = BoxShadow(0.px, (-4).px, 8.px, 0.px, Color("rgba(0, 0, 0, 0.15)"))
        }

        // Drag handle
        div {
            css {
                display = Display.flex
                justifyContent = JustifyContent.center
                paddingTop = 8.px
                paddingBottom = 4.px
            }
            div {
                css {
                    width = 32.px
                    height = 4.px
                    background = M3Colors.onSurfaceVariant
                    borderRadius = 2.px
                    opacity = number(0.4)
                }
            }
        }

        // Header
        div {
            css {
                display = Display.flex
                alignItems = AlignItems.center
                justifyContent = JustifyContent.spaceBetween
                padding = 12.px
                paddingLeft = 24.px
                paddingRight = 24.px
                borderBottom = Border(1.px, LineStyle.solid, M3Colors.outlineVariant)
            }

            div {
                css {
                    display = Display.flex
                    alignItems = AlignItems.center
                    gap = 16.px
                }

                // Status indicator chip
                div {
                    css {
                        display = Display.flex
                        alignItems = AlignItems.center
                        gap = 8.px
                        padding = 6.px
                        paddingLeft = 12.px
                        paddingRight = 16.px
                        background = if (status.running) M3Colors.primaryContainer else M3Colors.surfaceVariant
                        borderRadius = 8.px
                    }

                    div {
                        css {
                            width = 8.px
                            height = 8.px
                            borderRadius = 50.pct
                            background = if (status.running) M3Colors.primary else M3Colors.outline
                        }
                    }

                    span {
                        css {
                            fontSize = 14.px
                            fontWeight = integer(500)
                            color = if (status.running) M3Colors.onPrimaryContainer else M3Colors.onSurfaceVariant
                        }
                        +if (status.running) "Pipeline Running" else "Pipeline Stopped"
                    }
                }

                // Progress indicator
                if (status.running && status.totalSteps > 0) {
                    div {
                        css {
                            display = Display.flex
                            alignItems = AlignItems.center
                            gap = 12.px
                        }

                        // Linear progress
                        div {
                            css {
                                width = 120.px
                                height = 4.px
                                background = M3Colors.surfaceVariant
                                borderRadius = 2.px
                                overflow = Overflow.hidden
                            }

                            div {
                                css {
                                    width = ((status.progress.toDouble() / status.totalSteps) * 100).pct
                                    height = 100.pct
                                    background = M3Colors.primary
                                    transition = Transition(TransitionProperty.all, 0.3.s, TransitionTimingFunction.ease)
                                }
                            }
                        }

                        span {
                            css {
                                fontSize = 12.px
                                color = M3Colors.onSurfaceVariant
                                fontFamily = string("'Roboto Mono', monospace")
                            }
                            +"${status.progress}/${status.totalSteps}"
                        }
                    }
                }

                // Current step
                status.currentStep?.let { step ->
                    span {
                        css {
                            fontSize = 14.px
                            color = M3Colors.onSurfaceVariant
                        }
                        +step
                    }
                }
            }

            // Close button - Icon button style
            button {
                css {
                    width = 40.px
                    height = 40.px
                    display = Display.flex
                    alignItems = AlignItems.center
                    justifyContent = JustifyContent.center
                    background = Color("transparent")
                    border = None.none
                    color = M3Colors.onSurfaceVariant
                    fontSize = 20.px
                    cursor = Cursor.pointer
                    borderRadius = 20.px
                    hover {
                        background = M3Colors.surfaceContainerHigh
                    }
                }
                onClick = { props.onClose() }
                +"✕"
            }
        }

        // Logs container
        div {
            ref = logContainerRef
            css {
                flexGrow = number(1.0)
                overflowY = Auto.auto
                padding = 16.px
                paddingLeft = 24.px
                paddingRight = 24.px
                fontFamily = string("'Roboto Mono', monospace")
                fontSize = 12.px
                lineHeight = number(1.7)
                background = M3Colors.surfaceContainerLow
            }

            if (logs.isEmpty()) {
                div {
                    css {
                        color = M3Colors.onSurfaceVariant
                        textAlign = TextAlign.center
                        padding = 32.px
                    }
                    +if (status.running) "Waiting for logs..." else "No logs available"
                }
            } else {
                logs.forEach { log ->
                    pre {
                        css {
                            margin = 0.px
                            padding = 4.px
                            paddingLeft = 0.px
                            paddingRight = 0.px
                            color = when (log.level) {
                                "ERROR" -> M3Colors.error
                                "WARN" -> M3Colors.tertiary
                                "INFO" -> M3Colors.primary
                                else -> M3Colors.onSurfaceVariant
                            }
                            whiteSpace = WhiteSpace.preWrap
                            wordBreak = WordBreak.breakAll
                        }

                        span {
                            css {
                                color = M3Colors.outline
                                marginRight = 8.px
                            }
                            +formatTimestamp(log.timestamp)
                        }

                        span {
                            css {
                                display = Display.inlineBlock
                                minWidth = 50.px
                                fontWeight = integer(500)
                                color = when (log.level) {
                                    "ERROR" -> M3Colors.error
                                    "WARN" -> M3Colors.tertiary
                                    "INFO" -> M3Colors.success
                                    else -> M3Colors.onSurfaceVariant
                                }
                                marginRight = 8.px
                            }
                            +"[${log.level}]"
                        }

                        span {
                            css {
                                color = M3Colors.onSurface
                            }
                            +log.message
                        }
                    }
                }
            }
        }

        // Error banner
        status.error?.let { error ->
            div {
                css {
                    padding = 16.px
                    paddingLeft = 24.px
                    paddingRight = 24.px
                    background = M3Colors.errorContainer
                    borderTop = Border(1.px, LineStyle.solid, M3Colors.error)
                    color = M3Colors.onErrorContainer
                    fontSize = 14.px
                    fontWeight = integer(500)
                }
                +"Error: $error"
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val date = js("new Date(timestamp)").unsafeCast<dynamic>()
    val hours = date.getHours().toString().padStart(2, '0')
    val minutes = date.getMinutes().toString().padStart(2, '0')
    val seconds = date.getSeconds().toString().padStart(2, '0')
    return "$hours:$minutes:$seconds"
}
