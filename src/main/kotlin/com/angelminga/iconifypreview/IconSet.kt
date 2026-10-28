package com.angelminga.iconifypreview

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The names of one icon set. [visible] is what completion offers; [all] also has hidden (deprecated) icons and
 * aliases, which still render and so are not reported as unknown.
 */
class IconSet(val visible: List<String>, val all: Set<String>) {

    companion object {
        /** Parses the API's `/collection?prefix=…` response. */
        fun parse(json: String): IconSet {
            val obj = JsonParser.parseString(json).asJsonObject
            val visible = sortedSetOf<String>()
            obj.strings("uncategorized").forEach(visible::add)
            obj.getAsJsonObject("categories")?.entrySet()?.forEach { (_, names) ->
                names.asJsonArray.forEach { visible += it.asString }
            }
            val all = HashSet<String>(visible)
            obj.strings("hidden").forEach(all::add)
            obj.getAsJsonObject("aliases")?.keySet()?.let(all::addAll)
            return IconSet(visible.toList(), all)
        }

        private fun JsonObject.strings(name: String): List<String> =
            getAsJsonArray(name)?.map { it.asString }.orEmpty()
    }
}
