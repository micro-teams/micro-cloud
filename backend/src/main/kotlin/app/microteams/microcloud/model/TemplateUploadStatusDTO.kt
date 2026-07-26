package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/** Values: pending,uploading,done,error */
enum class TemplateUploadStatusDTO(@get:JsonValue val value: kotlin.String) {

    pending("pending"),
    uploading("uploading"),
    done("done"),
    error("error");

    companion object {
        @JvmStatic
        @JsonCreator
        fun forValue(value: kotlin.String): TemplateUploadStatusDTO {
            return values().first { it -> it.value == value }
        }
    }
}
