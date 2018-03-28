package com.deviceinsight


import com.deviceinsight.AutoAuthUtils.extractRoutesFromRouteYml
import com.deviceinsight.AutoAuthUtils.extractUrl
import com.deviceinsight.AutoAuthUtils.findMatchingRoute
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import java.io.File
import java.lang.Math.min

/**
 * Goal which auto-fills the authorities. It also matches example requests with gateway routes
 * and outputs its findings (including missing matches)
 */
@Mojo(name = "auto-auth")
class AutoAuthMojo : AbstractMojo() {

	/**
	 * Base directory with snippets and documentation files
	 */
	@Parameter(property = "asciidocDirectory", required = true)
	private lateinit var asciidocDirectory: File

	/**
	 * Base directory with the gateway routes
	 */
	@Parameter(property = "gatewayRoutesDirectory", required = true)
	private lateinit var gatewayRoutesDirectory: File

	@Parameter(property = "ignoredGatewayRouteFiles")
	private lateinit var ignoredGatewayRouteFiles: List<String>

	@Parameter(property = "ignoredExampleRequestPaths")
	private lateinit var ignoredExampleRequestPaths: List<String>

	@Throws(MojoExecutionException::class)
	override fun execute() {

		try {

			val routes = findRoutes()
			val exampleRequests = findExampleRequests()
			val apiDocs = findApiDocs()

			val examplesToRouteMatches: Map<ExampleRequest, Route?> = findExampleRequestRouteMatches(exampleRequests, routes)

			val autoAuthReplacement = determineReplacements(exampleRequests, routes)

			val exampleRequestsWithoutRoute = findExampleRequestsWithoutRoute(examplesToRouteMatches)
			val routesWithoutExampleRequest = findRoutesWithoutExampleRequests(examplesToRouteMatches, routes)

			exampleRequestsWithoutRoute.forEach { log.warn("Example request without route: ${it.method} ${it.url}") }
			routesWithoutExampleRequest.forEach { log.warn("Route without example request: ${it.method} ${it.url}") }

			logRequestTable(examplesToRouteMatches)

			updateApiDocs(apiDocs, autoAuthReplacement)

		} catch (e: Exception) {
			throw MojoExecutionException("Error rewriting snippets: " + e.message, e)
		}
	}

	private fun updateApiDocs(apiDocs: Set<File>, autoAuthReplacement: Sequence<AutoAuthReplacement>) {
		apiDocs.forEach { apiDoc ->

			log.info("Processing ${apiDoc.absolutePath}")

			val text = apiDoc.readText()
			var updatedText = text

			autoAuthReplacement.forEach { replacement ->
				val token = "auto-auth:${replacement.exampleName}"

				if (text.contains(token)) {
					log.info("Doing the following replacement: $token to ${replacement.authoritiesString} in file ${apiDoc.absoluteFile}")
					updatedText = updatedText.replace(token, replacement.authoritiesString)
				}
			}

			if (text != updatedText) {
				log.info("Updating file: ${apiDoc.absoluteFile}")
				apiDoc.writeText(updatedText)
			}
		}
	}

	private fun findRoutesWithoutExampleRequests(examplesToRouteMatches: Map<ExampleRequest, Route?>, routes: Sequence<Route>) =
			routes.toSet().minus(examplesToRouteMatches.values.filterNotNull())

	private fun findExampleRequestsWithoutRoute(exampleRequestsToRoutes: Map<ExampleRequest, Route?>): Set<ExampleRequest> =
			exampleRequestsToRoutes.filter { (_, route) -> route == null }.keys

	private fun findExampleRequestRouteMatches(exampleRequests: Sequence<ExampleRequest>, routes: Sequence<Route>): Map<ExampleRequest, Route?> =
			exampleRequests.map { exampleRequest ->
				exampleRequest to findMatchingRoute(exampleRequest, routes)
			}.toMap()

	private fun determineReplacements(exampleRequests: Sequence<ExampleRequest>, routes: Sequence<Route>): Sequence<AutoAuthReplacement> =
			exampleRequests.mapNotNull { exampleRequest ->
				findMatchingRoute(exampleRequest, routes)?.let {
					AutoAuthReplacement(exampleRequest.exampleName, it.authorities.joinToString())
				}
			}

	private fun findExampleRequests(): Sequence<ExampleRequest> =
			asciidocDirectory.walkTopDown().filter { it.name == "http-request.adoc" }.map {
				val exampleName = it.parentFile.name
				val urlAndMethod = extractUrl(it)
				urlAndMethod?.let { ExampleRequest(urlAndMethod.method, urlAndMethod.url, exampleName) }
			}.filterNotNull().filter { request ->
				ignoredExampleRequestPaths.all { ignoredPath ->
					!request.url.startsWith(ignoredPath)
				}
			}

	private fun findApiDocs(): Set<File> = asciidocDirectory.walkTopDown().filter { it.extension == "adoc" && !isSnippet(it) }.toSet()

	private fun findRoutes(): Sequence<Route> {
		val routes = gatewayRoutesDirectory
				.walkTopDown()
				.filter { it.name.endsWith("-routes.yml") }
				.filter { !ignoredGatewayRouteFiles.contains(it.name) }
				.map {
					extractRoutesFromRouteYml(it)
				}.flatten()
		return routes
	}

	private fun logRequestTable(examplesToRouteMatches: Map<ExampleRequest, Route?>) {

		data class Row(val requestCell: String, val routeCell: String, val authorityCell: String)

		val table = examplesToRouteMatches.map { (request, route) ->

			val requestString = "${request.method} ${request.url}"

			val routeString = if (route != null) "${route.method} ${route.url}" else " - No route found - "

			val authorityString = route?.authorities?.joinToString() ?: ""

			Row(requestString, routeString, authorityString)

		}

		val width1 = min(table.map { it.requestCell.length }.max() ?: 0, 100)
		val width2 = min(table.map { it.routeCell.length }.max() ?: 0, 100)
		val width3 = min(table.map { it.authorityCell.length }.max() ?: 0, 100)

		val line = "-".repeat(width1 + width2 + width3 + " | ".length * 2)

		log.info(line)
		log.info("""${"EXAMPLE REQUEST".padEnd(width1)} | ${"ROUTE".padEnd(width2)} | ${"AUTHORITIES".padEnd(width3)}""")
		log.info(line)

		table.forEach {
			val rowString = "${it.requestCell.take(width1).padEnd(width1)} | ${it.routeCell.take(width2).padEnd(width2)} | ${it.authorityCell.take(width3).padEnd(width3)}"
			log.info(rowString)
		}

		log.info(line)
	}

}
