package com.deviceinsight.helmdeploymavenplugin

import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import java.io.File


/**
 * Packages helm charts
 */
abstract class AbstractPackageMojo : AbstractHelmMojo() {

	companion object {
		private val PLACEHOLDER_REGEX = Regex("""\$\{(.*)}""")
	}

	@Parameter(property = "chartRepoUrl", required = true)
	private lateinit var chartRepoUrl: String

	@Parameter(property = "chartFolder", required = false)
	private var chartFolder: String? = null

	@Parameter(property = "helm.skip", defaultValue = "false")
	private var skip: Boolean = false


	@Throws(MojoExecutionException::class)
	override fun execute() {

		if (skip) {
			log.info("helm-package has been skipped")
			return
		}

		try {

			val targetHelmDir = File(target(), chartName())

			log.info("Clear target directory to ensure clean target package")
			if (targetHelmDir.exists()) {
				targetHelmDir.deleteRecursively()
			}
			targetHelmDir.mkdirs()
			log.info("Created target helm directory")

			processHelmConfigFiles(targetHelmDir)

			val helm = resolveHelmBinary()

			executeCmd("$helm init --client-only")
			executeCmd("$helm repo add incubator https://kubernetes-charts-incubator.storage.googleapis.com")
			executeCmd("$helm repo add chartRepo $chartRepoUrl")
			executeCmd("$helm dependency update", directory = targetHelmDir)
			executeCmd("$helm package ${chartName()} --version ${project.model.version}")

			ensureChartFileExists()

		} catch (e: Exception) {
			throw MojoExecutionException("Error creating helm chart: ${e.message}", e)
		}
	}

	private fun ensureChartFileExists() {

		val chartTarGzFile = chartTarGzFile()

		if (!chartTarGzFile.exists()) {
			throw RuntimeException("File ${chartTarGzFile.absolutePath} not found. " +
					"Chart must be created in package phase first.")
		} else {
			log.info("Successfully packaged chart and saved it to: $chartTarGzFile")
		}
	}

	private fun processHelmConfigFiles(targetHelmDir: File) {
		val directory = File("${project.basedir}/${chartFolder()}")
		log.debug("Processing helm files in directory ${directory.absolutePath}")
		val processedFiles = directory.walkTopDown().filter { it.isFile }.onEach { file ->
			log.debug("Processing helm file ${file.absolutePath}")
			val targetFile = targetHelmDir.resolve(file.toRelativeString(directory))
			log.debug("Copying to ${targetFile.absolutePath}")
			targetFile.apply {
				parentFile.mkdirs()
			}

			targetFile.bufferedWriter().use { writer ->
				file.useLines { lines ->
					lines.map { line ->
						PLACEHOLDER_REGEX.replace(line) { matchResult ->
							val property = matchResult.groupValues[1]
							val propertyValue = findPropertyValue(property)

							when (propertyValue) {
								null -> matchResult.groupValues[0]
								else -> propertyValue
							}
						}
					}.forEach {
						writer.appendln(it)
					}
				}
			}

		}.toList()

		if (processedFiles.isEmpty()) {
			throw IllegalStateException("No helm files found in ${directory.absolutePath}")
		}
	}

	private fun chartFolder() = chartFolder ?: "src/main/helm/${chartName()}"

	private fun findPropertyValue(property: String): CharSequence? {
		return when (property) {
			"project.version" -> project.version
			"artifactId" -> project.artifactId
			else -> project.properties.getProperty(property)
		}
	}

}

/**
 * define mojo for goal "helm-package"
 */
@Mojo(name = "helm-package", defaultPhase = LifecyclePhase.PACKAGE)
class HelmPackageMojo : AbstractPackageMojo()

/**
 * define mojo for goal "package"
 */
@Mojo(name = "package", defaultPhase = LifecyclePhase.PACKAGE)
class PackageMojo : AbstractPackageMojo()

