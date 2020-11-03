/*
 * Copyright 2018-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.deviceinsight.helm

import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.RequestBuilder
import org.apache.http.entity.FileEntity
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Publishes helm charts
 */
@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY)
class DeployMojo : AbstractMojo() {

	companion object {
		private val deployAtEndDeploymentRequests: MutableList<ChartDeploymentRequest> =
			Collections.synchronizedList(mutableListOf())

		private val readyProjectsCounter: AtomicInteger = AtomicInteger()
	}

	/**
	 * Name of the chart
	 */
	@Parameter(property = "chartName", required = false)
	private var chartName: String? = null

	@Parameter(property = "chartVersion", required = false)
	private var chartVersion: String? = null

	@Parameter(property = "chartRepoUrl", required = false)
	private var chartRepoUrl: String? = null

	@Parameter(property = "chartPublishUrl", required = false)
	private var chartPublishUrl: String? = null

	@Parameter(property = "chartPublishMethod", required = false, defaultValue = "POST")
	private var chartPublishMethod: String = "POST"

	@Parameter(property = "chartDeleteUrl", required = false)
	private var chartDeleteUrl: String? = null

	@Parameter(property = "chartRepoUsername", required = false)
	private var chartRepoUsername: String? = null

	@Parameter(property = "chartRepoPassword", required = false)
	private var chartRepoPassword: String? = null
	@Parameter(property = "skipSnapshots", required = false, defaultValue = "true")
	private var skipSnapshots: Boolean = true

	@Parameter(defaultValue = "\${project}", readonly = true, required = true)
	private lateinit var project: MavenProject

	@Parameter(defaultValue = "\${reactorProjects}", required = true, readonly = true)
	private lateinit var reactorProjects: List<MavenProject>

	@Parameter(property = "helm.skip", defaultValue = "false")
	private var skip: Boolean = false

	@Parameter(property = "helm.deployAtEnd", defaultValue = "false")
	private var deployAtEnd: Boolean = false

	@Throws(MojoExecutionException::class)
	override fun execute() {

		if (skip) {
			log.info("helm-deploy has been skipped")
			return
		}

		try {

			val chartDeploymentRequest =
				ChartDeploymentRequest(chartName, chartVersion, chartPublishMethod, chartRepoUrl, chartPublishUrl,
					chartDeleteUrl, chartRepoUsername, chartRepoPassword, skipSnapshots, project)

			if (!deployAtEnd && (skipSnapshots && chartDeploymentRequest.isSnapshotVersion())) {
				log.info("Version contains SNAPSHOT and 'skipSnapshots' option is enabled. Not publishing.")
				return
			}

			var deployAtEndRequested = false

			if (deployAtEnd) {
				deployAtEndDeploymentRequests.add(chartDeploymentRequest)
				deployAtEndRequested = true
			} else {
				publishToRepo(chartDeploymentRequest)
			}

			val projectsReady = readyProjectsCounter.incrementAndGet() == reactorProjects.size

			if (projectsReady) {
				synchronized(deployAtEndDeploymentRequests) {
					deployAtEndDeploymentRequests.forEach { publishToRepo(it) }
				}
			} else if (deployAtEndRequested) {
				log.info("Deploy helm chart: ${chartDeploymentRequest.chartName()} at end.")
			}

		} catch (e: Exception) {
			throw MojoExecutionException("Error creating/publishing helm chart: ${e.message}", e)
		}
	}

	private fun publishToRepo(chartDeploymentRequest: ChartDeploymentRequest) {

		if (!chartFileExists(chartDeploymentRequest)) {
			log.info("Skip module ${chartDeploymentRequest.chartName()}. There is no tar file present.")
			return
		}

		if (chartDeploymentRequest.isSnapshotVersion()) {
			removeChartIfExists(chartDeploymentRequest)
		}

		publishChart(chartDeploymentRequest)
	}

	private fun chartFileExists(chartDeploymentRequest: ChartDeploymentRequest): Boolean {

		val chartTarGzFile = chartDeploymentRequest.chartTarGzFile()
		log.debug("Tar file location: ${chartTarGzFile.absolutePath}.")

		return chartTarGzFile.exists()
	}

	private fun publishChart(chartDeploymentRequest: ChartDeploymentRequest) {

		val chartTarGzFile = chartDeploymentRequest.chartTarGzFile()

		createChartRepoClient(chartDeploymentRequest).use { httpClient ->

			val request = RequestBuilder
				.create(chartDeploymentRequest.chartPublishMethod)
				.setUri(chartDeploymentRequest.chartPublishUrl())
				.setEntity(FileEntity(chartTarGzFile))
				.build()

			httpClient.execute(request).use { response ->
				val statusCode = response.statusLine.statusCode
				if ( (statusCode >= 200) && (statusCode < 300) ) {
					throw RuntimeException("Unexpected status code when executing $chartPublishMethod request to " +
						"chart repo ${chartDeploymentRequest.chartPublishUrl()}: $statusCode")
				}
				log.info("$chartTarGzFile uploaded successfully")
			}
		}
	}

	private fun removeChartIfExists(chartDeploymentRequest: ChartDeploymentRequest) {

		createChartRepoClient(chartDeploymentRequest).use { httpClient ->
			httpClient.execute(HttpDelete(chartDeploymentRequest.chartDeleteUrl())).use { response ->
				if (response.statusLine.statusCode == 200) {
					log.info("Existing chart removed successfully")
				}
			}
		}
	}

	private fun createChartRepoClient(chartDeploymentRequest: ChartDeploymentRequest): CloseableHttpClient {
		val clientBuilder = HttpClientBuilder.create()
			// cookie management is disabled to avoid parsing errors of the Set-Cookie header
			// see https://github.com/deviceinsight/helm-maven-plugin/issues/45
			.disableCookieManagement()

		if (chartDeploymentRequest.chartRepoUsername != null && chartDeploymentRequest.chartRepoPassword != null) {
			clientBuilder.setDefaultCredentialsProvider(BasicCredentialsProvider().apply {
				setCredentials(
					AuthScope.ANY,
					UsernamePasswordCredentials(chartDeploymentRequest.chartRepoUsername,
						chartDeploymentRequest.chartRepoPassword)
				)
			})
		}

		return clientBuilder.build()

	}

}

data class ChartDeploymentRequest(private val chartName: String?, private val chartVersion: String?,
								  val chartPublishMethod: String, private val chartRepoUrl: String?,
								  private val chartPublishUrl: String?, private val chartDeleteUrl: String?,
								  val chartRepoUsername: String?, val chartRepoPassword: String?,
								  val skipSnapshots: Boolean, val project: MavenProject) {

	fun chartName(): String = chartName ?: project.artifactId

	private fun chartVersion(): String = chartVersion ?: project.version

	fun isSnapshotVersion(): Boolean = chartVersion().endsWith("-SNAPSHOT")

	fun chartPublishUrl(): String = chartPublishUrl ?: "$chartRepoUrl/api/charts"

	fun chartDeleteUrl(): String = chartDeleteUrl ?: "$chartRepoUrl/api/charts/${chartName()}/${chartVersion()}"

	fun chartTarGzFile() = target().resolve("${chartName()}-${chartVersion()}.tgz")

	private fun target() = File(project.build.directory).resolve("helm")

}
