package com.druk.lmplayground.tools

import org.json.JSONObject
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Live current date/time. The date baked into the system prompt is frozen at
 * session creation, so anything time-sensitive should call this instead.
 * Offline, deterministic, no permission, not sensitive.
 */
class DateTimeTool : Tool {
    override val name = "current_datetime"
    override val description = "Get the current local date and time, day of week, time zone, and the equivalent UTC time. Use this for anything time-sensitive; do not rely on your training data or the system prompt for today's date."
    override val parametersSchema = """{"type":"object","properties":{}}"""

    override fun execute(arguments: String): String {
        return try {
            val now = ZonedDateTime.now()
            JSONObject()
                .put("iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .put("date", now.format(DATE))
                .put("time", now.format(TIME))
                .put("day_of_week", now.format(DOW))
                .put("timezone", now.zone.id)
                .put("utc_offset", now.offset.id)
                .put("utc", ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .put("unix_seconds", now.toEpochSecond())
                .toString()
        } catch (e: Exception) {
            """{"error":"${(e.message ?: "Could not read the current time").replace("\"", "'")}"}"""
        }
    }

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
        private val DOW = DateTimeFormatter.ofPattern("EEEE", Locale.US)
    }
}
