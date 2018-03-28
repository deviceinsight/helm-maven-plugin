package com.deviceinsight


import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Goal which applies the configured rewrite rules to snippets in the configured directory
 */
@Mojo(name = "rewrite-rules")
class RewriteMojo : AbstractMojo() {

	/**
	 * Location of the rule config file
	 */
	@Parameter(property = "rewriteRules", required = true)
	private lateinit var rewriteRules: File

	/**
	 * Base directory with the snippets
	 */
	@Parameter(property = "snippetsDirectory", required = true)
	private lateinit var snippetsDirectory: File

	@Throws(MojoExecutionException::class)
	override fun execute() {

		try {
			val f = rewriteRules

			val ruleConfig = ConfigParser().parse(fileToString(f))

			snippetsDirectory.walkTopDown().filter { isSnippet(it) }.forEach {
				UrlRewriteUtils.rewriteUrls(it, ruleConfig)
			}

		} catch (e: Exception) {
			throw MojoExecutionException("Error rewriting snippets: " + e.message, e)
		}
	}

	@Throws(IOException::class)
	private fun fileToString(f: File): String {
		return String(Files.readAllBytes(f.toPath()), Charsets.UTF_8)
	}
}
