package com.deviceinsight.helmdeploymavenplugin

import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.FileEntity
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.client.HttpClients
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
@Mojo(name = "helm-package", defaultPhase = LifecyclePhase.DEPLOY)
class HelmPackageMojo : AbstractMojo() {

	/**
	 * Name of the chart
	 */
	@Parameter(property = "chartName", required = false)
	private var chartName: String? = null

	@Parameter(property = "chartRepoUrl", required = true)
	private lateinit var chartRepoUrl: String

	@Parameter(property = "chartRepoUsername", required = false)
	private var chartRepoUsername: String? = null

	@Parameter(property = "chartRepoPassword", required = false)
	private var chartRepoPassword: String? = null

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

			executeCmd("$helm init --client-only")
			executeCmd("$helm repo add chartRepo $chartRepoUrl")
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

			log.info("Using downloaded helm client ${helmTmpBinary.absolutePath}")
			helmTmpBinary.setExecutable(true)
			return helmTmpBinary.absolutePath
		} ?: (helmBinary ?: "helm")
	}

	private fun processHelmConfigFiles(targetHelmDir: File) {
		val directory = File("${project.basedir}/src/main/helm/${chartName()}")
		log.info("Processing helm files in directory ${directory.absolutePath}")
		directory.walkTopDown().filter { it.isFile }.forEach { file ->
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

		createChartRepoClient().use { httpClient ->
			val httpPost = HttpPost(url).apply { entity = FileEntity(chartTarGzFile) }
			httpClient.execute(httpPost).use { response ->
				val statusCode = response.statusLine.statusCode
				if (statusCode != 201) {
					throw RuntimeException("Unexpected status code when POSTing to chart repo $url: $statusCode")
				}
				log.info("$chartTarGzFile posted successfully")
			}
		}
	}

	private fun removeChartIfExists() {

		val url = "$chartRepoUrl/api/charts/${chartName()}/${project.version}"

		createChartRepoClient().use { httpClient ->
			httpClient.execute(HttpDelete(url)).use { response ->
				if (response.statusLine.statusCode == 200) {
					log.info("Existing chart removed successfully")
				}
			}
		}
	}

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

	private fun createChartRepoClient(): CloseableHttpClient {
		val clientBuilder = HttpClientBuilder.create()

		if (chartRepoUsername != null && chartRepoPassword != null) {
			clientBuilder.setDefaultCredentialsProvider(BasicCredentialsProvider().apply { setCredentials(AuthScope.ANY, UsernamePasswordCredentials(chartRepoUsername, chartRepoPassword)) })
		}

		return clientBuilder.build()

	}

	private fun findPropertyValue(property: String): CharSequence? {
		return when (property) {
			"project.version" -> project.version
			"artifactId" -> project.artifactId
			else -> project.properties.getProperty(property)
		}
	}

	private fun chartTarGzFile() = target().resolve("${chartName()}-${project.version}.tgz")

	private fun target() = File(project.build.directory)

	private fun chartName() = chartName ?: project.artifactId

	private fun isSnapshotVersion() = project.version.contains("SNAPSHOT")
}
