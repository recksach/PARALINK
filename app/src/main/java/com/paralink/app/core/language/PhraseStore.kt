package com.paralink.app.core.language

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

interface PhraseStore {
    fun dictionary(source: Language, target: Language): Map<String, String>
}

class InMemoryPhraseStore(private val data: Map<Pair<Language, Language>, Map<String, String>>) : PhraseStore {
    override fun dictionary(source: Language, target: Language): Map<String, String> =
        data[source to target].orEmpty()
}

class JsonPhraseStore(input: InputStream) : PhraseStore {

    private val data: Map<Pair<Language, Language>, Map<String, String>> = buildData(input)

    override fun dictionary(source: Language, target: Language): Map<String, String> =
        data[source to target].orEmpty()

    private fun buildData(input: InputStream): Map<Pair<Language, Language>, Map<String, String>> {
        val root = JSONObject(input.readBytes().toString(Charsets.UTF_8))
        val result = HashMap<Pair<Language, Language>, Map<String, String>>()
        val pairs = root.optJSONArray("pairs") ?: JSONArray()
        for (i in 0 until pairs.length()) {
            val pair = pairs.getJSONObject(i)
            val src = Language.fromCode(pair.optString("src")) ?: continue
            val tgt = Language.fromCode(pair.optString("tgt")) ?: continue
            val entries = pair.optJSONArray("entries") ?: JSONArray()
            val dict = HashMap<String, String>()
            for (j in 0 until entries.length()) {
                val e = entries.getJSONObject(j)
                dict[e.optString("s")] = e.optString("t")
            }
            result[src to tgt] = dict
        }
        return result
    }
}