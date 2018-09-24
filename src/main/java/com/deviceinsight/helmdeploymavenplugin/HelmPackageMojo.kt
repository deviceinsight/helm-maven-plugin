package com.deviceinsight.helmdeploymavenplugin

import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClients
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import java.io.File


/**
 * Packages helm charts
 */
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

	@Parameter(property = "chartFolder", required = false)
	private var chartFolder: String? = null

	@Parameter(defaultValue = "\${project}", readonly = true, required = true)
	private lateinit var project: MavenProject

	@Parameter(property = "helm.skip", defaultValue = "false")
	private var skip: Boolean = false

	@Throws(MojoExecutionException::class)
	override fun execute() {

		if (skip) {
			log.info("helm-package has been skipped")
			return
		}

		try {

			validateConfiguration()

			val targetHelmDir = File(target(), chartName())

			log.info("Clear target directory to ensure clean target package")
			if (targetHelmDir.exists()) {
				targetHelmDir.deleteRecursively()
			}
			targetHelmDir.mkdirs()
			log.info("Created target helm directory")

			processHelmConfigFiles(targetHelmDir)

			val helm = determineHelmBinary()

			executeCmd("$helm init --client-only")
			executeCmd("$helm repo add incubator https://kubernetes-charts-incubator.storage.googleapis.com")
			executeCmd("$helm repo add chartRepo $chartRepoUrl")
			executeCmd("$helm dependency update", directory = targetHelmDir)
			executeCmd("$helm package ${chartName()}")

		} catch (e: Exception) {
			throw MojoExecutionException("Error creating helm chart: ${e.message}", e)
		}
	}

	private fun validateConfiguration() {
		check(!(helmBinaryFetchUrl != null && helmBinary != null),
			{ "Cannot set both 'helmBinaryFetchUrl' and 'helmBinary'" })
	}

	private fun determineHelmBinary(): String {

		return helmBinaryFetchUrl?.run {
			val helmTmpBinary = File(target(), "helm-command")
			if(!helmTmpBinary.exists()) {
				log.info("Downloading helm client from $helmBinaryFetchUrl")

				HttpClients.createDefault().use { httpClient ->
					httpClient.execute(HttpGet(helmBinaryFetchUrl)).use { response ->
						val statusCode = response.statusLine.statusCode
						if (statusCode != 200) {
							throw RuntimeException("Unexpected status code when downloading helm from $helmTmpBinary: $statusCode")
						}
						helmTmpBinary.outputStream().use {
							response.entity.writeTo(it)
						}
					}
				}
			}

			log.info("Using downloaded helm client ${helmTmpBinary.absolutePath}")
			helmTmpBinary.setExecutable(true)
			return helmTmpBinary.absolutePath
		} ?: (helmBinary ?: "helm")
	}

	private fun processHelmConfigFiles(targetHelmDir: File) {
		val directory = File("${project.basedir}/${chartFolder()}")
		log.info("Processing helm files in directory ${directory.absolutePath}")
		val processedFiles = directory.walkTopDown().filter { it.isFile }.onEach { file ->
			log.info("Processing helm file ${file.absolutePath}")
			val fileContents = file.readText()
			val updatedFileContents = Regex("\\$\\{(.*)}").replace(fileContents) { matchResult ->
				val property = matchResult.groupValues[1]
				val propertyValue = findPropertyValue(property)

				when (propertyValue) {
					null -> matchResult.groupValues[0]
					else -> propertyValue
				}
			}

			val targetFile = targetHelmDir.resolve(file.toRelativeString(directory))
			log.info("Copying to ${targetFile.absolutePath}")
			targetFile.apply {
				parentFile.mkdirs()
				writeText(updatedFileContents)
			}
		}.toList()

		if (processedFiles.isEmpty()) {
			throw IllegalStateException("No helm files found in ${directory.absolutePath}")
		}
	}

	private fun chartFolder() = chartFolder ?: "src/main/helm/${chartName()}"

	private fun executeCmd(cmd: String, directory: File = target()) {
		val proc = ProcessBuilder(cmd.split(" "))
				.directory(directory)
				.redirectOutput(ProcessBuilder.Redirect.PIPE)
				.redirectError(ProcessBuilder.Redirect.PIPE)
				.start()

		proc.waitFor()

		log.info("When executing '$cmd' in '${directory.absolutePath}', result was ${proc.exitValue()}")
		proc.inputStream.bufferedReader().lines().forEach { log.info("Output: $it") }
		proc.errorStream.bufferedReader().lines().forEach { log.info("Output: $it") }

		if (proc.exitValue() != 0) {
			throw RuntimeException("When executing '$cmd' got result code '${proc.exitValue()}'")
		}
	}

	private fun findPropertyValue(property: String): CharSequence? {
		return when (property) {
			"project.version" -> project.version
			"artifactId" -> project.artifactId
			else -> project.properties.getProperty(property)
		}
	}

	private fun target() = File(project.build.directory).resolve("helm")

	private fun chartName() = chartName ?: project.artifactId

}
