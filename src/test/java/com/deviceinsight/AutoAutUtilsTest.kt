package com.deviceinsight

import com.deviceinsight.AutoAuthUtils.extractRoutesFromRouteYml
import com.deviceinsight.AutoAuthUtils.findMatchingRoute
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class AutoAutUtilsTest {

	@Test
	fun `test that routes are extracted from route yml`() {

		val routeYml = """
					gateway:
					  forwards:
					    timespace:
					     - req: GET /api/v2/assets/urn/{urn}/datapoints/**
					       authorities: READ_DATAPOINT
					    ng-core:
					     - req: GET /api/v2/assets
					     - req: GET /api/v2/gateways
					       authorities:
					        - READ_ASSET
					        - WRITE_ASSET
			""".trimIndent().replace("\t", "    ")

		val routes = extractRoutesFromRouteYml(routeYml)

		assertThat(routes).containsExactly(
				Route("GET", "/api/v2/assets/urn/{urn}/datapoints/**", listOf("READ_DATAPOINT")),
				Route("GET", "/api/v2/assets", listOf()),
				Route("GET", "/api/v2/gateways", listOf("READ_ASSET", "WRITE_ASSET")))
	}

	@Test
	fun `test that multiple method notation in route yml is supported`() {

		val routeYml = """
					gateway:
					  forwards:
					    timespace:
					     - req: PUT|POST /api/v2/assets/urn/{urn}/datapoints/**
					       authorities: UPDATE_DATAPOINT
			""".trimIndent().replace("\t", "    ")

		val routes = extractRoutesFromRouteYml(routeYml)

		assertThat(routes).containsExactly(
				Route("PUT", "/api/v2/assets/urn/{urn}/datapoints/**", listOf("UPDATE_DATAPOINT")),
				Route("POST", "/api/v2/assets/urn/{urn}/datapoints/**", listOf("UPDATE_DATAPOINT")))
	}

	@Test
	fun `test that empty route list is returned, if file does not contain routes`() {

		val routeYml = """
					something:
					  else: is here
			""".trimIndent().replace("\t", "    ")

		assertThat(extractRoutesFromRouteYml(routeYml)).isEmpty()
	}

	@Test
	fun `test that routes for example requests can be found`() {

		val routes = sequenceOf(Route("GET", "/v2/assets/{urn}/datapointValues/**", listOf("READ_DATAPOINT")))
		val request = ExampleRequest("GET", "/v2/assets/urn:123/datapointValues/entries", "list-datapoint-values")

		assertThat(findMatchingRoute(request, routes)).isEqualTo(routes.first())
	}

	@Test
	fun `test that route with single star matches`() {

		val routes = sequenceOf(Route("GET", "/v2/assets/*/datapointValues", listOf("READ_DATAPOINT")))
		val request = ExampleRequest("GET", "/v2/assets/urn:123/datapointValues", "list-datapoint-values")

		assertThat(findMatchingRoute(request, routes)).isEqualTo(routes.first())
	}

	@Test
	fun `test that a route with a star at the end matches`() {

		val routes = sequenceOf(Route("PUT", "/api/v2/accounts/*"))
		val request = ExampleRequest("PUT", "/api/v2/accounts/9b249b55-5bc0-4356-9bb3-acc6353f6eb5", "update-account")

		assertThat(findMatchingRoute(request, routes)).isEqualTo(routes.first())
	}

	@Test
	fun `test that a route with multiple method matches`() {

		val routes = sequenceOf(Route("PUT", "/api/v2/accounts/*"))
		val request = ExampleRequest("PUT", "/api/v2/accounts/9b249b55-5bc0-4356-9bb3-acc6353f6eb5", "update-account")

		assertThat(findMatchingRoute(request, routes)).isEqualTo(routes.first())
	}

	@Test
	fun `test that null is returned if request cannot be found`() {

		val routes = sequenceOf(Route("GET", "/v2/assets/{urn}/datapointValues/**", listOf("READ_DATAPOINT")))
		val request = ExampleRequest("GET", "/v2/gateways/urn:123/datapointValues", "list-datapoint-values")

		assertThat(findMatchingRoute(request, routes)).isNull()
	}

	@Test
	fun `test that similar prefix route does not match request`() {
		val routes = sequenceOf(Route("GET", " /api/v2/assets/{id}"))
		val request = ExampleRequest("GET", "/api/v2/assets/urn:di:assets:sn:123/datapoints/temp/double/samples?slices=2&start=1970-01-01T00%3A00%3A01Z&end=1970-01-01T00%3A00%3A03Z", "list-datapoint-samples")

		assertThat(findMatchingRoute(request, routes)).isNull()
	}

	@Test
	fun `test that routes for example requests does not match if method is different`() {

		val routes = sequenceOf(Route("GET", "/v2/assets/{urn}/datapointValues/**", listOf("READ_DATAPOINT")))
		val request = ExampleRequest("DELETE", "/v2/assets/urn:123/datapointValues/entries", "list-datapoint-values")

		assertThat(findMatchingRoute(request, routes)).isNull()
	}
}
