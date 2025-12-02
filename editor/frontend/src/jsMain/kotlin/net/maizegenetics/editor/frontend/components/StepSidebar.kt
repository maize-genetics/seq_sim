package net.maizegenetics.editor.frontend.components

import emotion.react.css
import net.maizegenetics.editor.frontend.M3Colors
import net.maizegenetics.editor.shared.PIPELINE_STEPS
import net.maizegenetics.editor.shared.PipelineConfig
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.span
import web.cssom.*
import web.html.InputType

external interface StepSidebarProps : Props {
    var config: PipelineConfig
    var selectedStep: String?
    var onSelectStep: (String) -> Unit
    var onToggleStep: (String) -> Unit
}

val StepSidebar = FC<StepSidebarProps> { props ->
    val enabledSteps = props.config.runSteps ?: emptyList()

    div {
        css {
            width = 300.px
            background = M3Colors.surfaceContainerLowest
            borderRight = Border(1.px, LineStyle.solid, M3Colors.outlineVariant)
            display = Display.flex
            flexDirection = FlexDirection.column
            overflow = Overflow.hidden
        }

        // Header - Navigation Rail style
        div {
            css {
                padding = 16.px
                paddingLeft = 24.px
                paddingRight = 24.px
                borderBottom = Border(1.px, LineStyle.solid, M3Colors.outlineVariant)
                fontWeight = integer(500)
                fontSize = 14.px
                color = M3Colors.onSurfaceVariant
                textTransform = TextTransform.uppercase
                letterSpacing = 0.5.px
            }
            +"Pipeline Steps"
        }

        // Steps list
        div {
            css {
                flexGrow = number(1.0)
                overflowY = Auto.auto
                paddingTop = 8.px
                paddingBottom = 8.px
            }

            // Main pipeline steps (1-5)
            div {
                css {
                    padding = 12.px
                    paddingLeft = 24.px
                    fontSize = 11.px
                    fontWeight = integer(500)
                    color = M3Colors.onSurfaceVariant
                    textTransform = TextTransform.uppercase
                    letterSpacing = 0.5.px
                }
                +"Variant Simulation"
            }

            PIPELINE_STEPS.filter { it.stepNumber <= 5 }.forEach { step ->
                StepItem {
                    this.step = step
                    this.isEnabled = step.id in enabledSteps
                    this.isSelected = step.id == props.selectedStep
                    this.onSelect = { props.onSelectStep(step.id) }
                    this.onToggle = { props.onToggleStep(step.id) }
                }
            }

            // Recombination pipeline steps (6-10)
            div {
                css {
                    paddingTop = 20.px
                    paddingBottom = 12.px
                    paddingLeft = 24.px
                    paddingRight = 24.px
                    fontSize = 11.px
                    fontWeight = integer(500)
                    color = M3Colors.onSurfaceVariant
                    textTransform = TextTransform.uppercase
                    letterSpacing = 0.5.px
                }
                +"Recombination"
            }

            PIPELINE_STEPS.filter { it.stepNumber > 5 }.forEach { step ->
                StepItem {
                    this.step = step
                    this.isEnabled = step.id in enabledSteps
                    this.isSelected = step.id == props.selectedStep
                    this.onSelect = { props.onSelectStep(step.id) }
                    this.onToggle = { props.onToggleStep(step.id) }
                }
            }
        }
    }
}

external interface StepItemProps : Props {
    var step: net.maizegenetics.editor.shared.StepMetadata
    var isEnabled: Boolean
    var isSelected: Boolean
    var onSelect: () -> Unit
    var onToggle: () -> Unit
}

val StepItem = FC<StepItemProps> { props ->
    div {
        css {
            display = Display.flex
            alignItems = AlignItems.center
            padding = 12.px
            paddingLeft = 16.px
            paddingRight = 16.px
            marginLeft = 8.px
            marginRight = 8.px
            marginBottom = 4.px
            gap = 12.px
            cursor = Cursor.pointer
            borderRadius = 28.px
            background = if (props.isSelected) M3Colors.secondaryContainer else Color("transparent")
            hover {
                background = if (props.isSelected) M3Colors.secondaryContainer else M3Colors.surfaceContainerHigh
            }
        }
        onClick = { props.onSelect() }

        // Checkbox - Material 3 style
        label {
            css {
                display = Display.flex
                alignItems = AlignItems.center
                cursor = Cursor.pointer
            }
            onClick = { e ->
                e.stopPropagation()
            }

            input {
                type = InputType.checkbox
                checked = props.isEnabled
                onChange = {
                    props.onToggle()
                }
                css {
                    width = 18.px
                    height = 18.px
                    accentColor = M3Colors.primary
                    cursor = Cursor.pointer
                }
            }
        }

        // Step info
        div {
            css {
                flexGrow = number(1.0)
                minWidth = 0.px
            }

            div {
                css {
                    display = Display.flex
                    alignItems = AlignItems.center
                    gap = 8.px
                }

                span {
                    css {
                        fontSize = 12.px
                        fontWeight = integer(500)
                        color = if (props.isSelected) M3Colors.onSecondaryContainer else M3Colors.onSurfaceVariant
                        fontFamily = string("'Roboto Mono', monospace")
                    }
                    +"${props.step.stepNumber.toString().padStart(2, '0')}"
                }

                span {
                    css {
                        fontSize = 14.px
                        fontWeight = integer(500)
                        color = if (props.isSelected) M3Colors.onSecondaryContainer 
                               else if (props.isEnabled) M3Colors.onSurface 
                               else M3Colors.onSurfaceVariant
                        whiteSpace = WhiteSpace.nowrap
                        overflow = Overflow.hidden
                        textOverflow = TextOverflow.ellipsis
                    }
                    +props.step.name
                }
            }
        }
    }
}
