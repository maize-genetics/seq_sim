package net.maizegenetics.editor.frontend

import emotion.react.css
import net.maizegenetics.editor.frontend.components.*
import net.maizegenetics.editor.shared.*
import react.*
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.header
import react.dom.html.ReactHTML.main
import react.dom.html.ReactHTML.span
import web.cssom.*

// Material 3 Light Theme Colors
object M3Colors {
    val primary = Color("#6750A4")
    val onPrimary = Color("#FFFFFF")
    val primaryContainer = Color("#EADDFF")
    val onPrimaryContainer = Color("#21005D")
    
    val secondary = Color("#625B71")
    val onSecondary = Color("#FFFFFF")
    val secondaryContainer = Color("#E8DEF8")
    val onSecondaryContainer = Color("#1D192B")
    
    val tertiary = Color("#7D5260")
    val onTertiary = Color("#FFFFFF")
    val tertiaryContainer = Color("#FFD8E4")
    val onTertiaryContainer = Color("#31111D")
    
    val error = Color("#B3261E")
    val onError = Color("#FFFFFF")
    val errorContainer = Color("#F9DEDC")
    val onErrorContainer = Color("#410E0B")
    
    val surface = Color("#FEF7FF")
    val onSurface = Color("#1D1B20")
    val surfaceVariant = Color("#E7E0EC")
    val onSurfaceVariant = Color("#49454F")
    val surfaceContainerLowest = Color("#FFFFFF")
    val surfaceContainerLow = Color("#F7F2FA")
    val surfaceContainer = Color("#F3EDF7")
    val surfaceContainerHigh = Color("#ECE6F0")
    val surfaceContainerHighest = Color("#E6E0E9")
    
    val outline = Color("#79747E")
    val outlineVariant = Color("#CAC4D0")
    
    val inverseSurface = Color("#322F35")
    val inverseOnSurface = Color("#F5EFF7")
    val inversePrimary = Color("#D0BCFF")
    
    val success = Color("#386A1F")
    val successContainer = Color("#B8F397")
    val onSuccessContainer = Color("#052100")
}

val App = FC<Props> {
    var config by useState<PipelineConfig?>(null)
    var selectedStep by useState<String?>(null)
    var status by useState<PipelineStatus?>(null)
    var isLoading by useState(true)
    var errorMessage by useState<String?>(null)
    var successMessage by useState<String?>(null)
    var showStatusPanel by useState(false)

    // Load config on mount
    useEffectOnce {
        ApiClient.loadConfig { result ->
            result.fold(
                onSuccess = { response ->
                    config = response.config
                    isLoading = false
                },
                onFailure = { error ->
                    errorMessage = "Failed to load config: ${error.message}"
                    isLoading = false
                }
            )
        }
    }

    // Poll for status when pipeline is running
    useEffect(status?.running) {
        if (status?.running == true) {
            val intervalId = kotlinx.browser.window.setInterval({
                ApiClient.getStatus { result ->
                    result.fold(
                        onSuccess = { newStatus -> status = newStatus },
                        onFailure = { }
                    )
                }
            }, 2000)
        }
    }

    fun handleSave() {
        config?.let { cfg ->
            ApiClient.saveConfig(cfg) { result ->
                result.fold(
                    onSuccess = { response ->
                        if (response.success) {
                            successMessage = "Configuration saved!"
                            kotlinx.browser.window.setTimeout({ successMessage = null }, 3000)
                        } else {
                            errorMessage = response.message
                        }
                    },
                    onFailure = { error ->
                        errorMessage = "Failed to save: ${error.message}"
                    }
                )
            }
        }
    }

    fun handleRun() {
        config?.let { cfg ->
            ApiClient.runPipeline(cfg) { result ->
                result.fold(
                    onSuccess = {
                        status = PipelineStatus(running = true, currentStep = "Starting...")
                        showStatusPanel = true
                        successMessage = "Pipeline started!"
                        kotlinx.browser.window.setTimeout({ successMessage = null }, 3000)
                    },
                    onFailure = { error ->
                        errorMessage = "Failed to start pipeline: ${error.message}"
                    }
                )
            }
        }
    }

    fun handleStop() {
        ApiClient.stopPipeline { result ->
            result.fold(
                onSuccess = {
                    status = status?.copy(running = false)
                    successMessage = "Pipeline stopped"
                    kotlinx.browser.window.setTimeout({ successMessage = null }, 3000)
                },
                onFailure = { error ->
                    errorMessage = "Failed to stop pipeline: ${error.message}"
                }
            )
        }
    }

    fun updateConfig(newConfig: PipelineConfig) {
        config = newConfig
    }

    div {
        css {
            height = 100.vh
            display = Display.flex
            flexDirection = FlexDirection.column
            background = M3Colors.surface
        }

        // Header - Material 3 Top App Bar
        header {
            css {
                display = Display.flex
                alignItems = AlignItems.center
                justifyContent = JustifyContent.spaceBetween
                padding = 16.px
                paddingLeft = 24.px
                paddingRight = 24.px
                background = M3Colors.surface
                boxShadow = BoxShadow(0.px, 1.px, 3.px, 1.px, Color("rgba(0, 0, 0, 0.15)"))
                zIndex = integer(10)
            }

            h1 {
                css {
                    fontSize = 22.px
                    fontWeight = integer(400)
                    color = M3Colors.onSurface
                    letterSpacing = 0.px
                }
                +"Pipeline Configuration Editor"
            }

            div {
                css {
                    display = Display.flex
                    gap = 12.px
                    alignItems = AlignItems.center
                }

                // Status indicator chip
                status?.let { s ->
                    if (s.running) {
                        span {
                            css {
                                display = Display.flex
                                alignItems = AlignItems.center
                                gap = 8.px
                                padding = 6.px
                                paddingLeft = 16.px
                                paddingRight = 16.px
                                background = M3Colors.primaryContainer
                                color = M3Colors.onPrimaryContainer
                                borderRadius = 8.px
                                fontSize = 14.px
                                fontWeight = integer(500)
                            }
                            +"Running: ${s.currentStep ?: "..."}"
                        }
                    }
                }

                // Success message chip
                successMessage?.let { msg ->
                    span {
                        css {
                            padding = 6.px
                            paddingLeft = 16.px
                            paddingRight = 16.px
                            background = M3Colors.successContainer
                            color = M3Colors.onSuccessContainer
                            borderRadius = 8.px
                            fontSize = 14.px
                            fontWeight = integer(500)
                        }
                        +msg
                    }
                }

                // Error message chip
                errorMessage?.let { msg ->
                    span {
                        css {
                            padding = 6.px
                            paddingLeft = 16.px
                            paddingRight = 16.px
                            background = M3Colors.errorContainer
                            color = M3Colors.onErrorContainer
                            borderRadius = 8.px
                            fontSize = 14.px
                            fontWeight = integer(500)
                        }
                        +msg
                    }
                }

                // Save button - Filled tonal
                button {
                    css {
                        padding = 10.px
                        paddingLeft = 24.px
                        paddingRight = 24.px
                        background = M3Colors.secondaryContainer
                        color = M3Colors.onSecondaryContainer
                        border = None.none
                        borderRadius = 20.px
                        fontSize = 14.px
                        fontWeight = integer(500)
                        cursor = Cursor.pointer
                        letterSpacing = 0.1.px
                        hover {
                            background = Color("#D9D0E8")
                        }
                    }
                    onClick = { handleSave() }
                    +"Save"
                }

                if (status?.running == true) {
                    // Stop button - Filled with error color
                    button {
                        css {
                            padding = 10.px
                            paddingLeft = 24.px
                            paddingRight = 24.px
                            background = M3Colors.error
                            color = M3Colors.onError
                            border = None.none
                            borderRadius = 20.px
                            fontSize = 14.px
                            fontWeight = integer(500)
                            cursor = Cursor.pointer
                            letterSpacing = 0.1.px
                            hover {
                                background = Color("#9C2018")
                            }
                        }
                        onClick = { handleStop() }
                        +"Stop"
                    }
                } else {
                    // Run button - Filled primary
                    button {
                        css {
                            padding = 10.px
                            paddingLeft = 24.px
                            paddingRight = 24.px
                            background = M3Colors.primary
                            color = M3Colors.onPrimary
                            border = None.none
                            borderRadius = 20.px
                            fontSize = 14.px
                            fontWeight = integer(500)
                            cursor = Cursor.pointer
                            letterSpacing = 0.1.px
                            hover {
                                background = Color("#5B4497")
                            }
                        }
                        onClick = { handleRun() }
                        +"Run Pipeline"
                    }
                }
            }
        }

        // Main content
        main {
            css {
                display = Display.flex
                flexGrow = number(1.0)
                overflow = Overflow.hidden
                background = M3Colors.surfaceContainerLow
            }

            if (isLoading) {
                div {
                    css {
                        display = Display.flex
                        alignItems = AlignItems.center
                        justifyContent = JustifyContent.center
                        width = 100.pct
                        color = M3Colors.onSurfaceVariant
                    }
                    +"Loading..."
                }
            } else {
                config?.let { cfg ->
                    // Step sidebar
                    StepSidebar {
                        this.config = cfg
                        this.selectedStep = selectedStep
                        this.onSelectStep = { stepId -> selectedStep = stepId }
                        this.onToggleStep = { stepId ->
                            val currentSteps = cfg.runSteps ?: emptyList()
                            val newSteps = if (stepId in currentSteps) {
                                currentSteps - stepId
                            } else {
                                currentSteps + stepId
                            }
                            updateConfig(cfg.copy(runSteps = newSteps.sortedBy { s ->
                                PIPELINE_STEPS.find { it.id == s }?.stepNumber ?: 99
                            }))
                        }
                    }

                    // Editor panel
                    div {
                        css {
                            flexGrow = number(1.0)
                            display = Display.flex
                            flexDirection = FlexDirection.column
                            overflow = Overflow.hidden
                        }

                        if (selectedStep != null) {
                            StepEditor {
                                this.stepId = selectedStep!!
                                this.config = cfg
                                this.onConfigChange = { newConfig -> updateConfig(newConfig) }
                            }
                        } else {
                            // Welcome panel
                            div {
                                css {
                                    display = Display.flex
                                    flexDirection = FlexDirection.column
                                    alignItems = AlignItems.center
                                    justifyContent = JustifyContent.center
                                    height = 100.pct
                                    color = M3Colors.onSurfaceVariant
                                    gap = 16.px
                                }
                                div {
                                    css { fontSize = 48.px }
                                    +"📋"
                                }
                                div {
                                    css { 
                                        fontSize = 16.px
                                        fontWeight = integer(400)
                                    }
                                    +"Select a step from the sidebar to configure"
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pipeline status panel
        if (showStatusPanel) {
            PipelineStatusPanel {
                this.status = status
                this.onClose = { showStatusPanel = false }
            }
        }
    }
}
