package com.deviceinsight

import org.assertj.core.api.Java6Assertions.assertThat
import org.junit.Test

class RewriteConfigParserTest {

	@Test
	fun `test that simple yaml can be parsed`() {

		// @formatter:off
		val yaml = """
			rules:
			 - from: f
			   to: t
			 """.trimIndent()
		 // @formatter:on

		val (rules) = ConfigParser().parse(yaml)

		assertThat(rules).containsExactly(RewriteRule("f", "t"))
	}

}
