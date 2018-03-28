package com.deviceinsight

import java.io.File
import java.net.URI

object UrlRewriteUtils {

	private val LOCATION_PATTERN = Regex("Location: (.+)")

	private val TABLE_HEADER_PATTERN = Regex("^\\.(/.+)")

	fun rewriteUrls(f: File, config: RewriteRuleConfig) {

		val modifiedContent = rewriteUrls(f.readText(), config)
		f.writeText(modifiedContent)
	}

	fun rewriteUrls(contents: String, config: RewriteRuleConfig) =
			replaceLocationHeaders(
					replaceUrlsAndMethods(
							replaceTableHeader(contents, config), config), config)

	private fun replaceLocationHeaders(contentsWithUrlReplaced: String, config: RewriteRuleConfig) =
		LOCATION_PATTERN.replace(contentsWithUrlReplaced, { m ->
			"Location: ${rewriteLocationHeaderValue(m.groups[1]!!.value, config)}"
		})

	private fun replaceUrlsAndMethods(contents: String, config: RewriteRuleConfig) =
		URL_PATTERN.replace(contents, { m ->
			"${m.groups[1]!!.value} ${rewriteUrl(m.groups[2]!!.value, config)} ${m.groups[3]!!.value}"
		})

	private fun replaceTableHeader(contents: String, config: RewriteRuleConfig) =
		TABLE_HEADER_PATTERN.replace(contents, { m ->
			".${rewriteUrl(m.groups[1]!!.value, config)}"
		})

	private fun rewriteUrl(url: String, config: RewriteRuleConfig): String {

		val matchingRule: RewriteRule? = config.rules.firstOrNull { url.startsWith(it.from) }

		return matchingRule?.let {
			url.replaceFirst(it.from, it.to)
		} ?: url
	}

	private fun rewriteLocationHeaderValue(locationHeaderValue: String, config: RewriteRuleConfig): String {

		val uri = URI(locationHeaderValue)

		return config.rules.firstOrNull { uri.path.startsWith(it.from) }?.let {
			"${uri.scheme}://${uri.authority}${uri.path.replaceFirst(it.from, it.to)}${uri.query ?: ""}${uri.fragment ?: ""}"
		} ?: locationHeaderValue
	}
}
