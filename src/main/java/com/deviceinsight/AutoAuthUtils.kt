package com.deviceinsight

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File


object AutoAuthUtils {

	fun extractUrl(f: File): UrlAndMethod? = URL_PATTERN.find(f.readText())?.let { UrlAndMethod(it.groups[2]!!.value, it.groups[1]!!.value) }

	fun extractRoutesFromRouteYml(f: File): List<Route> = extractRoutesFromRouteYml(f.readText())

	fun extractRoutesFromRouteYml(s: String): List<Route> {

		val objectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
		val yml = objectMapper.readTree(s)

		return yml.get("gateway")?.get("forwards")?.toList()?.flatMap {

			it.elements().asSequence().toList().flatMap {
				val (methods, url) = it["req"].asText().split(" ", limit = 2)
				val authoritiesNode = it["authorities"]
				val authorities: List<String> = when {
					authoritiesNode == null -> emptyList()
					authoritiesNode.isTextual -> listOf(authoritiesNode.asText())
					else -> authoritiesNode.toList().map { it.asText() }
				}

				methods.split("|").map { Route(it, url, authorities) }
			}
		} ?: emptyList()
	}

	fun findMatchingRoute(request: ExampleRequest, routes: Sequence<Route>): Route? =
			routes.firstOrNull { route ->
				val urlPattern = route.url.
						replace(Regex("\\{.*\\}"), "[^/]+")
						.replace("**", ".*")
						.replace("/*/", "/[^/]+/")
						.replace("/*", "/[^/]+")

				val routeUrlPattern = Regex("^$urlPattern$")
				val baseRequestUrl = request.url.replace(Regex("\\?.*$"), "")

				route.method == request.method && routeUrlPattern.matches(baseRequestUrl)
			}

}
