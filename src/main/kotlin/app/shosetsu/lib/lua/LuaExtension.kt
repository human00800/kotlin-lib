package app.shosetsu.lib.lua

import app.shosetsu.lib.*
import org.json.JSONException
import org.json.JSONObject
import org.luaj.vm2.LuaString.EMPTYSTRING
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.LuaValue.*
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua.coerce
import org.luaj.vm2.lib.jse.CoerceLuaToJava
import java.io.File
import java.io.IOException
import java.security.InvalidParameterException

/*
 * This file is part of shosetsu-services.
 * shosetsu-services is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * shosetsu-services is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with shosetsu-services.  If not, see https://www.gnu.org/licenses/.
 */

/**
 * shosetsu-kotlin-lib
 * 16 / 01 / 2020
 *
 * @param content extension script
 */
class LuaExtension(val content: String) : IExtension {
	companion object {

		private const val KEY_ID = "id"
		private const val KEY_NAME = "name"
		private const val KEY_BASE_URL = "baseURL"
		private const val KEY_IMAGE_URL = "imageURL"

		private const val KEY_HAS_CLOUD_FLARE = "hasCloudFlare"

		private const val KEY_SEARCH = "search"
		private const val KEY_HAS_SEARCH = "hasSearch"
		private const val KEY_IS_SEARCH_INC = "isSearchIncrementing"
		private const val KEY_SEARCH_FILTERS = "searchFilters"

		private const val KEY_SETTINGS = "settings"
		private const val KEY_UPDATE_SETTING = "updateSetting"

		private const val KEY_CHAPTER_TYPE = "chapterType"
		private const val KEY_LISTINGS = "listings"
		private const val KEY_GET_PASSAGE = "getPassage"
		private const val KEY_PARSE_NOVEL = "parseNovel"

		private const val KEY_EXPAND_URL = "expandURL"
		private const val KEY_SHRINK_URL = "shrinkURL"

		/**
		 * Values that may not be present
		 */
		val defaults: Map<String, LuaValue> = mapOf(
				KEY_IMAGE_URL to EMPTYSTRING,
				KEY_HAS_CLOUD_FLARE to FALSE,
				KEY_HAS_SEARCH to TRUE,
				KEY_IS_SEARCH_INC to TRUE,
				KEY_SEARCH_FILTERS to LuaTable(),
				KEY_SETTINGS to LuaTable(),
				KEY_CHAPTER_TYPE to coerce(Novel.ChapterType.STRING)
		)

		/**
		 * Values that must be present
		 */
		val hardKeys: Map<String, Int> = mapOf(
				KEY_ID to TNUMBER,
				KEY_NAME to TSTRING,
				KEY_LISTINGS to TTABLE,
				KEY_GET_PASSAGE to TFUNCTION,
				KEY_PARSE_NOVEL to TFUNCTION
		)

		/**
		 * What is unique about soft keys is they are conditional on the soft key beforehand
		 * IE, if hasSearch is false, then search does not need to be present in script
		 */
		val softKeys: Map<String, Pair<Pair<String, Int>, (LuaValue) -> Boolean>> = mapOf(
				KEY_HAS_SEARCH to Pair(Pair(KEY_SEARCH, TFUNCTION), { v -> (v == TRUE) }),
				KEY_SETTINGS to Pair(Pair(KEY_UPDATE_SETTING, TFUNCTION), { v -> (v as LuaTable).length() != 0 })
		)

		private fun makeLuaReporter(f: (status: String) -> Unit) = object : OneArgFunction() {
			override fun call(p0: LuaValue?): LuaValue {
				f(p0!!.tojstring())
				return LuaValue.NIL
			}
		}

		private fun tableToFilters(table: LuaTable): Array<Filter<*>> =
				table.keys().map { table[it] }.filter { !it.isnil() }
						.map { CoerceLuaToJava.coerce(it, Any::class.java) as Filter<*> }.toTypedArray()


	}

	constructor(file: File) : this(file.readText())

	/**
	 * Returns the metadata that is at the header of the extension
	 */
	@Suppress("unused")
	override val metaData: JSONObject? by lazy {
		try {
			JSONObject(content.substring(0, content.indexOf("\n")).replace("--", "").trim())
		} catch (e: JSONException) {
			e.printStackTrace(); null
		} catch (e: IOException) {
			e.printStackTrace(); null
		}
	}

	private val source: LuaTable

	init {
		val globals = shosetsuGlobals()
		val l = try {
			globals.load(content)!!
		} catch (e: Error) {
			throw e
		}
		source = l.call() as LuaTable

		// If the modified default value doesnt exist, it assigns the default
		defaults.filter { source[it.key].isnil() }.forEach { source[it.key] = it.value }

		// If any required value is not found, it throws an NullPointerException
		with(hardKeys.filter { source.get(it.key).type() != it.value }.map { it.key }) {
			if (isNotEmpty())
				throw NullPointerException(
						"LuaScript has missing or invalid:" + fold("", { a, s -> "$a\n\t\t$s;" })
				)
		}

		// If any of the softKeys matching their condition of requirement are not found, it throws an NullPointerException
		with(softKeys.filter {
			val t = it.value.first
			if (it.value.second(source[it.key])) {
				source.get(t.first).type() != t.second
			} else false
		}.map { it.value.first.first }) {
			if (isNotEmpty())
				throw NullPointerException(
						"LuaScript has missing or invalid:" + fold("", { a, s -> "$a\n\t\t$s;" })
				)
		}

		// Ensures searchFilters conform to design
		tableToFilters(source[KEY_SEARCH_FILTERS] as LuaTable).let {
			println(it.contentDeepToString())
			if (it.any { it.id == QUERY_INDEX || it.id == PAGE_INDEX })
				throw InvalidParameterException("Search filters contain illegal ID $QUERY_INDEX||$PAGE_INDEX")
		}

	}

	override val name: String by lazy { source[KEY_NAME].tojstring() }
	override val baseURL: String by lazy { source[KEY_BASE_URL].tojstring() }
	override val formatterID by lazy { source[KEY_ID].toint() }
	override val imageURL: String by lazy { source[KEY_IMAGE_URL].tojstring() }
	override val hasCloudFlare by lazy { source[KEY_HAS_CLOUD_FLARE].toboolean() }
	override val hasSearch by lazy { source[KEY_HAS_SEARCH].toboolean() }
	override val isSearchIncrementing: Boolean by lazy { source[KEY_IS_SEARCH_INC].toboolean() }

	@Suppress("UNCHECKED_CAST")
	override val listings: Array<IExtension.Listing> by lazy {
		coerceLuaToJava<Array<IExtension.Listing>>(source[KEY_LISTINGS])
	}

	@Suppress("UNCHECKED_CAST")
	override val searchFiltersModel: Array<Filter<*>> by lazy {
		tableToFilters(source[KEY_SEARCH_FILTERS] as LuaTable)
	}
	override val chapterType: Novel.ChapterType by lazy {
		coerceLuaToJava<Novel.ChapterType>(source[KEY_CHAPTER_TYPE])
	}

	@Suppress("UNCHECKED_CAST")
	override val settingsModel: Array<Filter<*>> by lazy {
		tableToFilters(source[KEY_SETTINGS] as LuaTable)
	}

	override fun updateSetting(id: Int, value: Any?) {
		source[KEY_UPDATE_SETTING].takeIf { it.type() == TFUNCTION }?.call(valueOf(id), coerce(value)) ?: return
	}

	override fun getPassage(chapterURL: String): String =
			source[KEY_GET_PASSAGE].call(expandURL(chapterURL, 1)).tojstring()

	override fun parseNovel(novelURL: String, loadChapters: Boolean): Novel.Info =
			coerceLuaToJava(source[KEY_PARSE_NOVEL].call(
					valueOf(expandURL(novelURL, 1)),
					valueOf(loadChapters)
			))

	@Suppress("UNCHECKED_CAST")
	override fun search(data: Map<Int, *>): Array<Novel.Listing> =
			coerceLuaToJava(source[KEY_SEARCH].call(data.toLua()))

	override fun expandURL(smallURL: String, type: Int): String {
		val f = source[KEY_EXPAND_URL]
		if (f.type() != TFUNCTION) return smallURL
		return f.call(valueOf(smallURL), valueOf(type)).tojstring()
	}

	override fun shrinkURL(longURL: String, type: Int): String {
		val f = source[KEY_SHRINK_URL]
		if (f.type() != TFUNCTION) return longURL
		return f.call(valueOf(longURL), valueOf(type)).tojstring()
	}
}