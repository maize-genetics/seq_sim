package net.maizegenetics.webapp.components

import net.maizegenetics.webapp.model.StepSpec
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.checkbox

external interface StepCardProps : Props {
    var spec: StepSpec
    var enabled: Boolean
    var values: Map<String, String>
    var onToggle: (Boolean) -> Unit
    var onFieldChange: (String, String) -> Unit
}

/** A collapsible card for a single pipeline step, with an enable toggle and its fields. */
val StepCard = FC<StepCardProps> { props ->
    val (expanded, setExpanded) = useState(true)
    val step = props.spec

    div {
        className = ClassName(if (props.enabled) "card step-card" else "card step-card disabled")

        div {
            className = ClassName("step-head")
            label {
                className = ClassName("step-toggle")
                input {
                    type = InputType.checkbox
                    checked = props.enabled
                    onChange = { event -> props.onToggle(event.target.checked) }
                }
                span {
                    className = ClassName("step-title")
                    +step.title
                }
            }
            button {
                className = ClassName("collapse-btn")
                onClick = { setExpanded(!expanded) }
                +(if (expanded) "Hide" else "Show")
            }
        }

        if (step.description.isNotEmpty()) {
            p {
                className = ClassName("step-desc")
                +step.description
            }
        }

        if (props.enabled && expanded) {
            div {
                className = ClassName("fields")
                step.fields.forEach { field ->
                    FieldInput {
                        key = field.key
                        this.field = field
                        value = props.values[field.key] ?: ""
                        onValueChange = { v -> props.onFieldChange(field.key, v) }
                    }
                }
            }
        }
    }
}
