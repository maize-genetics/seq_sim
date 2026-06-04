package net.maizegenetics.webapp.components

import net.maizegenetics.webapp.model.FieldSpec
import net.maizegenetics.webapp.model.FieldType
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.span
import web.cssom.ClassName
import web.html.InputType
import web.html.checkbox
import web.html.number
import web.html.text

external interface FieldInputProps : Props {
    var field: FieldSpec
    var value: String
    var onValueChange: (String) -> Unit
}

/** Renders a single [FieldSpec] as a labelled form control. */
val FieldInput = FC<FieldInputProps> { props ->
    val field = props.field
    val missing = field.required && props.value.isBlank()

    div {
        className = ClassName("field")

        label {
            className = ClassName("field-label")
            +field.label
            if (field.required) {
                span {
                    className = ClassName("req")
                    +" *"
                }
            }
        }

        when (field.type) {
            FieldType.BOOL -> {
                label {
                    className = ClassName("checkbox-line")
                    input {
                        type = InputType.checkbox
                        checked = props.value == "true"
                        onChange = { event -> props.onValueChange(if (event.target.checked) "true" else "false") }
                    }
                    span { +(if (props.value == "true") "true" else "false") }
                }
            }

            FieldType.ENUM -> {
                select {
                    className = ClassName("text-input")
                    value = props.value
                    onChange = { event -> props.onValueChange(event.target.value) }
                    field.enumValues.forEach { opt ->
                        option {
                            value = opt
                            +opt
                        }
                    }
                }
            }

            FieldType.INT -> {
                input {
                    className = ClassName(if (missing) "text-input invalid" else "text-input")
                    type = InputType.number
                    value = props.value
                    placeholder = field.default ?: ""
                    onChange = { event -> props.onValueChange(event.target.value) }
                }
            }

            FieldType.TEXT -> {
                input {
                    className = ClassName(if (missing) "text-input invalid" else "text-input")
                    type = InputType.text
                    value = props.value
                    placeholder = field.default ?: ""
                    onChange = { event -> props.onValueChange(event.target.value) }
                }
            }
        }

        if (field.help.isNotEmpty()) {
            p {
                className = ClassName("field-help")
                +field.help
            }
        }
        if (missing) {
            p {
                className = ClassName("field-warn")
                +"This field is required."
            }
        }
    }
}
