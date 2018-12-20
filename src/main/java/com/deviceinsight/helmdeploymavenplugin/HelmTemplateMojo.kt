package com.deviceinsight.helmdeploymavenplugin

import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import java.io.File

abstract class AbstractTemplateMojo : AbstractHelmMojo() {

	/**
	 * An optional values.yaml file that is used to render the template, relative to `${project.basedir}`.
	 */
	@Parameter(property = "valuesFile", required = false)
	private var valuesFile: String? = null

	/**
	 * The file where the output should be rendered to, either an absolute file or relative to `${project.basedir}`.
	 */
	@Parameter(property = "outputFile", defaultValue = "\${project.build.directory}/test-classes/helm.yaml")
	private lateinit var outputFile: String

	@Parameter(property = "helm.skip", defaultValue = "false")
	private var skip: Boolean = false

	@Throws(MojoExecutionException::class)
	override fun execute() {

		if (skip) {
			log.info("helm-template has been skipped")
			return
		}

		try {

			val helm = resolveHelmBinary()

			val command = if (valuesFile != null) {
				val valuesFilePath = project.basedir.resolve(valuesFile!!).absolutePath
				"$helm template --values $valuesFilePath ${chartName()}"
			} else {
				"$helm template ${chartName()}"
			}

			var file = File(outputFile)
			if (!file.isAbsolute) {
				file = project.basedir.resolve(file)
			}

			executeCmd(command, redirectOutput = ProcessBuilder.Redirect.to(file))

			log.info("Rendered helm template to ${file.absolutePath}")

		} catch (e: Exception) {
			throw MojoExecutionException("Error rendering helm templates: ${e.message}", e)
		}
	}
}

/**
 * define mojo for goal "helm-template"
 */
@Mojo(name = "helm-template", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST)
class HelmTemplateMojo : AbstractTemplateMojo()

/**
 * define mojo for goal "template"
 */
@Mojo(name = "template", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST)
class TemplateMojo : AbstractTemplateMojo()

