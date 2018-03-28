package com.deviceinsight

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class UrlRewriteUtilsTest {

	@Test
	fun `test that URLs are correctly replaced`() {

		val snippet = """
			[source,http,options="nowrap"]
			----
			GET /timespace/entries?size=2 HTTP/1.1
			X-TENANT-ID: test_tenant
			Accept: application/json
			Host: localhost:8080
			Location: http://localhost:8080/timespace/moreEntries
			----
			""".trimIndent()

		val config = RewriteRuleConfig(rules = listOf(RewriteRule("/timespace", "/api/v2")))
		val modifiedContents = UrlRewriteUtils.rewriteUrls(snippet, config)

		// @formatter:off
		assertThat(modifiedContents)
				.containsPattern("\nGET /api/v2/entries\\?size=2 HTTP/1\\.1\n")
				.containsPattern("\nLocation: http://localhost:8080/api/v2/moreEntries\n")
				.doesNotContain("/timespace")
		// @formatter:on
	}

	@Test
	fun `test that table header in path paramrs is correctly replaced`() {

		val snippet = """
			./api/events/stateful/{assetId}/eventkeys
			|===
			|Parameter|Description

			|`assetId`
			|The ID of the asset

			|===
			""".trimIndent()

		val config = RewriteRuleConfig(rules = listOf(RewriteRule("/api/events", "/api/v2/events")))
		val modifiedContents = UrlRewriteUtils.rewriteUrls(snippet, config)

		assertThat(modifiedContents).contains("./api/v2/events/stateful/{assetId}/eventkeys")
	}
}
