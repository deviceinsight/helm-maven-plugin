package com.deviceinsight.helmdeploymavenplugin

import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.result.Result
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import java.io.File

/**
 * Packages and publishes helm charts
 */
//@Mojo(name = "helm-package", defaultPhase = LifecyclePhase.DEPLOY)
@Mojo(name = "helm-package", defaultPhase = LifecyclePhase.PACKAGE)
class HelmPackageMojo : AbstractMojo() {

	/**
	 * Name of the chart
	 */
	@Parameter(property = "chartName", required = false)
	private var chartName: String? = null

	@Parameter(property = "chartRepoUrl", required = true)
	private lateinit var chartRepoUrl: String

	/**
	 * The path to the helm command line client, defaults to `helm`.
	 */
	@Parameter(property = "helmBinary", required = false)
	private var helmBinary: String? = null

	@Parameter(property = "helmBinaryFetchUrl", required = false)
	private var helmBinaryFetchUrl: String? = null

	@Parameter(defaultValue = "\${project}", readonly = true, required = true)
	private lateinit var project: MavenProject

	@Throws(MojoExecutionException::class)
	override fun execute() {

		try {

			validateConfiguration()

			val targetHelmDir = File(target(), chartName())
			targetHelmDir.mkdirs()

			processHelmConfigFiles(targetHelmDir)

			val helm = determineHelmBinary()

			executeCmd("$helm dependency update", directory = targetHelmDir)
			executeCmd("$helm package ${chartName()}")

			publishToRepo()

		} catch (e: Exception) {
			throw MojoExecutionException("Error creating/publishing helm chart: ${e.message}", e)
		}
	}

	private fun validateConfiguration() {
		check(!(helmBinaryFetchUrl != null && helmBinary != null),
				{ "Cannot set both 'helmBinaryFetchUrl' and 'helmBinary'" })
	}

	private fun determineHelmBinary(): String {

		return helmBinaryFetchUrl?.let {
			val helmTmpBinary = File.createTempFile("helm", "")
			log.info("Downloading helm client from $helmBinaryFetchUrl")
			Fuel.download(it).destination { _, _ -> helmTmpBinary }
					.response { _, _, result -> if (result is Result.Failure) {
						throw RuntimeException("Could not download helm binary from $helmBinaryFetchUrl")
					} }.response()

			log.info("Using downloaded helm client ${helmTmpBinary.absolutePath}")
			helmTmpBinary.setExecutable(true)
			return helmTmpBinary.absolutePath
		} ?: (helmBinary ?: "helm")
	}

	private fun processHelmConfigFiles(targetHelmDir: File) {
		val directory = "${project.basedir}/src/main/helm"
		log.info("Processing helm files in directory $directory")
		File(directory).walkTopDown().filter { it.isFile }.forEach { file ->
			log.info("Copying and processing helm file ${file.absolutePath}")
			val fileContents = file.readText()
			val updatedFileContents = Regex("\\$\\{(.*)}").replace(fileContents) { matchResult ->
				val property = matchResult.groupValues[1]
				val propertyValue = findPropertyValue(property)

				when (propertyValue) {
					null -> matchResult.groupValues[0]
					else -> propertyValue
				}
			}

			targetHelmDir.resolve(file.name).writeText(updatedFileContents)
		}
	}

	private fun publishToRepo() {

		ensureChartFileExists()

		if (isSnapshotVersion()) {
			removeChartIfExists()
		}

		publishChart()
	}

	private fun ensureChartFileExists() {

		val chartTarGzFile = chartTarGzFile()

		if (!chartTarGzFile.exists()) {
			throw RuntimeException("File ${chartTarGzFile.absolutePath} not found")
		}
	}

	private fun publishChart() {

		val chartTarGzFile = chartTarGzFile()

		val url = "$chartRepoUrl/api/charts"

		val (_, response, result) = Fuel.post(url).body(chartTarGzFile.readBytes()).responseString()

		when (result) {
			is Result.Failure -> throw RuntimeException("Error posting to chart repo '$url': ${result.error.exception.message}")

			else -> if (response.httpStatusCode in listOf(200, 201)) {
				log.info("$chartTarGzFile posted successfully")
			} else {
				throw RuntimeException("There was an error POSTing $chartTarGzFile to $url. " +
						"Result was HTTP ${response.httpStatusCode}: ${response.httpResponseMessage}")
			}
		}
	}

	private fun removeChartIfExists() {
		Fuel.delete("$chartRepoUrl/api/charts/${chartName()}/${project.version}").responseString()
	}

	private fun chartTarGzFile() = target().resolve("${chartName()}-${project.version}.tgz")

	private fun executeCmd(cmd: String, directory: File = target()) {
		val proc = ProcessBuilder(cmd.split(" "))
				.directory(directory)
				.redirectOutput(ProcessBuilder.Redirect.PIPE)
				.redirectError(ProcessBuilder.Redirect.PIPE)
				.start()

		proc.waitFor()

		log.info("When executing '$cmd' in '${directory.absolutePath}', result was ${proc.exitValue()}")
		log.info("Begin of output")
		proc.inputStream.bufferedReader().lines().forEach { log.info("Output: $it") }
		proc.errorStream.bufferedReader().lines().forEach { log.info("Output: $it") }
		log.info("End of output")

		if (proc.exitValue() != 0) {
			throw RuntimeException("When executing '$cmd' got result code '${proc.exitValue()}'")
		}
	}

	private fun target() = File(project.build.directory)

	private fun findPropertyValue(property: String): CharSequence? {
		return when (property) {
			"project.version" -> project.version
			"artifactId" -> project.artifactId
			else -> project.properties.getProperty(property)
		}
	}

	private fun chartName() = chartName ?: project.artifactId

	private fun isSnapshotVersion() = project.version.contains("SNAPSHOT")
}
