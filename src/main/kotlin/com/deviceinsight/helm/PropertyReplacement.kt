package com.deviceinsight.helm

import org.apache.maven.plugins.annotations.Parameter
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.PathMatcher

class PropertyReplacement {

	@Parameter(property = "exclusions")
	var exclusions: List<String> = emptyList()

	private val exclusionPatchMatchers = lazy { exclusions.toPathMatchers() }

	companion object {
		private val SUBSTITUTED_EXTENSIONS = setOf("json", "tpl", "yml", "yaml")
		private val PATH_MATCHER_PATTERN = Regex("^(glob|regex):.*$")
	}

	fun isPropertyReplacementCandidate(file: File): Boolean {
		val extension = file.extension.lowercase()
		return SUBSTITUTED_EXTENSIONS.contains(extension)
			&& file.doesNotMatchAnyExclusion()
	}

	private fun File.doesNotMatchAnyExclusion() = exclusionPatchMatchers.value.none { it.matches(this.toPath()) }

	private fun List<String>.toPathMatchers(): List<PathMatcher> = this
		.map { prependGlobIfMissing(it) }
		.map { FileSystems.getDefault().getPathMatcher(it) }

	private fun prependGlobIfMissing(path: String): String = if (PATH_MATCHER_PATTERN.matches(path)) path else "glob:$path"

}

