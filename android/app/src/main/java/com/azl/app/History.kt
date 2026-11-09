package com.azl.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val time: Long,
    val title: String,
    val mode: Mode,
    val results: List<ResultItem>,
)

/** Past separations, kept in a small JSON file. The audio/video files themselves live in the gallery. */
object History {
    private const val MAX = 100
    private fun file(ctx: Context) = File(ctx.filesDir, "history.json")

    @Synchronized
    fun load(ctx: Context): List<HistoryEntry> = runCatching {
        val arr = JSONArray(file(ctx).readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val res = o.getJSONArray("results")
            HistoryEntry(
                time = o.getLong("time"),
                title = o.getString("title"),
                mode = runCatching { Mode.valueOf(o.getString("mode")) }.getOrDefault(Mode.SPEECH),
                results = (0 until res.length()).map { j ->
                    val r = res.getJSONObject(j)
                    val peaks = r.optJSONArray("peaks")?.let { p -> FloatArray(p.length()) { p.getDouble(it).toFloat() } }
                    ResultItem(r.getString("label"), Uri.parse(r.getString("uri")), r.getString("mime"), peaks)
                },
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(ctx: Context, e: HistoryEntry) = save(ctx, (listOf(e) + load(ctx)).take(MAX))

    @Synchronized
    fun remove(ctx: Context, time: Long) = save(ctx, load(ctx).filter { it.time != time })

    @Synchronized
    fun clear(ctx: Context) = save(ctx, emptyList())

    private fun save(ctx: Context, list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("time", e.time)
                put("title", e.title)
                put("mode", e.mode.name)
                put("results", JSONArray().apply {
                    e.results.forEach { r ->
                        put(JSONObject().apply {
                            put("label", r.label)
                            put("uri", r.uri.toString())
                            put("mime", r.mime)
                            r.peaks?.let { p -> put("peaks", JSONArray().apply { p.forEach { put(Math.round(it * 100) / 100.0) } }) }
                        })
                    }
                })
            })
        }
        file(ctx).writeText(arr.toString())
    }
}
