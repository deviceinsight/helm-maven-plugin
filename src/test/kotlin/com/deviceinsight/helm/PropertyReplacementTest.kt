package com.deviceinsight.helm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class PropertyReplacementTest {

	@ParameterizedTest
	@ValueSource(strings = ["test.txt", "test.xml"])
	fun `isPropertyReplacementCandidate should return false if file extension is not in inclusion list`(filename: String) {
		val file = File(filename)
		val propertyReplacement = PropertyReplacement()
		assertThat(propertyReplacement.isPropertyReplacementCandidate(file)).isFalse
	}

	@ParameterizedTest
	@ValueSource(strings = ["test.json", "test.tpl", "test.yml", "test.yaml"])
	fun `isPropertyReplacementCandidate should return true if file extension is in inclusion list`(filename: String) {
		val file = File(filename)
		val propertyReplacement = PropertyReplacement()
		assertThat(propertyReplacement.isPropertyReplacementCandidate(file)).isTrue
	}

	@ParameterizedTest
	@ValueSource(strings = ["dashboards/test.json", "templates/test.json", "templates/default/test.json"])
	fun `isPropertyReplacementCandidate should return false if file matches exclusion pattern`(filename: String) {
		val file = File(filename)
		val propertyReplacement = PropertyReplacement()
		propertyReplacement.exclusions =
			listOf("regex:dashboards/.*\\.json", "glob:templates/*.json", "templates/**/*.json")
		assertThat(propertyReplacement.isPropertyReplacementCandidate(file)).isFalse
	}
}
