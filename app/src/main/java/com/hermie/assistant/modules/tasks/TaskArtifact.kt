package com.hermie.assistant.modules.tasks

import org.json.JSONArray
import org.json.JSONObject

// ── Supporting data classes ────────────────────────────────────────────────

data class Poi(val name: String, val address: String, val lat: Double, val lon: Double)
data class EventRef(val title: String, val start: String, val end: String)
data class Link(val title: String, val url: String, val snippet: String)

/**
 * Structured output produced by a completed task.
 *
 * The LLM signals completion with:
 *   `<done type="ARTIFACT_TYPE">{...json...}</done>`
 *
 * TaskManager parses this into the appropriate sealed class variant and
 * attaches it to the [Task]. The UI then renders an appropriate chip on the
 * home screen and a detail card in TasksScreen.
 */
sealed class TaskArtifact {
    abstract val type: String

    data class MessageDraft(
        val contact: String,
        val body: String,
        val channel: String  // "sms", "whatsapp", "email"
    ) : TaskArtifact() {
        override val type = "message_draft"
    }

    data class PoiList(val pois: List<Poi>) : TaskArtifact() {
        override val type = "poi_list"
    }

    data class EventList(val events: List<EventRef>) : TaskArtifact() {
        override val type = "event_list"
    }

    data class Summary(
        val text: String,
        val sources: List<String> = emptyList()
    ) : TaskArtifact() {
        override val type = "summary"
    }

    data class LinkList(val links: List<Link>) : TaskArtifact() {
        override val type = "link_list"
    }

    object None : TaskArtifact() {
        override val type = "none"
    }

    companion object {
        /**
         * Parse the JSON body of a `<done type="...">` tag into the correct variant.
         * Returns null if the type is unknown or the JSON cannot be parsed.
         */
        fun parse(artifactType: String, json: String): TaskArtifact? {
            return try {
                val obj = runCatching { JSONObject(json.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                when (artifactType.lowercase()) {
                    "message_draft" -> MessageDraft(
                        contact = obj.optString("contact"),
                        body = obj.optString("body"),
                        channel = obj.optString("channel", "sms")
                    )
                    "poi_list" -> {
                        val arr = obj.optJSONArray("pois") ?: JSONArray()
                        PoiList((0 until arr.length()).map { i ->
                            val p = arr.getJSONObject(i)
                            Poi(p.optString("name"), p.optString("address"),
                                p.optDouble("lat", 0.0), p.optDouble("lon", 0.0))
                        })
                    }
                    "event_list" -> {
                        val arr = obj.optJSONArray("events") ?: JSONArray()
                        EventList((0 until arr.length()).map { i ->
                            val e = arr.getJSONObject(i)
                            EventRef(e.optString("title"), e.optString("start"), e.optString("end"))
                        })
                    }
                    "summary" -> Summary(
                        text = obj.optString("text"),
                        sources = obj.optJSONArray("sources")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: emptyList()
                    )
                    "link_list" -> {
                        val arr = obj.optJSONArray("links") ?: JSONArray()
                        LinkList((0 until arr.length()).map { i ->
                            val l = arr.getJSONObject(i)
                            Link(l.optString("title"), l.optString("url"), l.optString("snippet"))
                        })
                    }
                    "none", "" -> None
                    else -> None
                }
            } catch (e: Exception) {
                null
            }
        }

        /** Serialize to JSON for disk storage in TaskStore. */
        fun toJson(artifact: TaskArtifact): JSONObject {
            val obj = JSONObject()
            obj.put("type", artifact.type)
            when (artifact) {
                is MessageDraft -> {
                    obj.put("contact", artifact.contact)
                    obj.put("body", artifact.body)
                    obj.put("channel", artifact.channel)
                }
                is PoiList -> {
                    val arr = JSONArray()
                    artifact.pois.forEach { p ->
                        arr.put(JSONObject().also {
                            it.put("name", p.name); it.put("address", p.address)
                            it.put("lat", p.lat); it.put("lon", p.lon)
                        })
                    }
                    obj.put("pois", arr)
                }
                is EventList -> {
                    val arr = JSONArray()
                    artifact.events.forEach { e ->
                        arr.put(JSONObject().also {
                            it.put("title", e.title); it.put("start", e.start); it.put("end", e.end)
                        })
                    }
                    obj.put("events", arr)
                }
                is Summary -> {
                    obj.put("text", artifact.text)
                    val arr = JSONArray()
                    artifact.sources.forEach { arr.put(it) }
                    obj.put("sources", arr)
                }
                is LinkList -> {
                    val arr = JSONArray()
                    artifact.links.forEach { l ->
                        arr.put(JSONObject().also {
                            it.put("title", l.title); it.put("url", l.url); it.put("snippet", l.snippet)
                        })
                    }
                    obj.put("links", arr)
                }
                is None -> { /* no extra fields */ }
            }
            return obj
        }

        /** Deserialize from the JSON stored by TaskStore. */
        fun fromJson(obj: JSONObject): TaskArtifact? {
            val type = obj.optString("type").ifEmpty { return null }
            // Reconstruct json string for re-parsing through parse()
            return parse(type, obj.toString())
        }
    }
}
