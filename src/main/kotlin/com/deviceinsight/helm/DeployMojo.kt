/*
 * Copyright 2018-2023 the original author or authors.
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

import com.deviceinsight.helm.model.Repo
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.RequestBuilder
import org.apache.http.entity.FileEntity
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Publishes helm charts
 */
@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY)
class DeployMojo : AbstractHelmMojo() {

	companion object {
		private val deployAtEndDeploymentRequests: MutableList<ChartDeploymentRequest> =
			Collections.synchronizedList(mutableListOf())

		private val readyProjectsCounter: AtomicInteger = AtomicInteger()
	}

	@Parameter(property = "chartRepoName", required = false)
	private var chartRepoName: String? = null

	@Parameter(property = "chartRegistryUrl", required = false)
	private var chartRegistryUrl: String? = null

	@Parameter(property = "deployAtEnd", defaultValue = "false")
	private var deployAtEnd: Boolean = false

	@Parameter(property = "skipSnapshots", required = false, defaultValue = "true")
	private var skipSnapshots: Boolean = true


	@Parameter(defaultValue = "\${reactorProjects}", required = true, readonly = true)
	private lateinit var reactorProjects: List<MavenProject>

	override fun runMojo() {
		check(chartRepoName == null || chartRegistryUrl == null) {
			"chartRepoName and chartRegistryUrl must not be set at the same time"
		}

		if (chartRepoName == null && chartRegistryUrl == null) {
			// If there is only one repo or registry specified we autodetect the name/url property

			if (repos.size == 1 && registries.isEmpty()) {
				chartRepoName = repos.first().name
			} else if (registries.size == 1 && repos.isEmpty()) {
				chartRegistryUrl = registries.first().url
			} else if (registries.isEmpty() && repos.isEmpty()) {
				error("Please configure at least on chart repository of registry")
			} else {
				error(
					"It is necessary to specify chartRepoName or chartRegistryUrl explicitly " +
						"since there is more than one chart repo / chart registry configured"
				)
			}
		}

		val deploymentRequest = createDeploymentRequest()
		if (deploymentRequest is ChartRegistryDeploymentRequest) {
			validateAndAddRegistries()
		}

		if (skipSnapshots && chartVersion.endsWith("-SNAPSHOT")) {
			log.info("Version contains SNAPSHOT and 'skipSnapshots' option is enabled. Not publishing.")
		} else if (deployAtEnd) {
			deployAtEndDeploymentRequests.add(deploymentRequest)
			log.info("Deploy helm chart: $chartName at end.")
		} else {
			executeDeploymentRequest(deploymentRequest)
		}

		val projectsReady = readyProjectsCounter.incrementAndGet() == reactorProjects.size
		if (projectsReady && deployAtEndDeploymentRequests.isNotEmpty()) {
			deployAtEndDeploymentRequests.forEach { executeDeploymentRequest(it) }
		}
	}

	private fun createDeploymentRequest() = if (chartRepoName != null) {
		val deployRepo = checkNotNull(repos.find { it.name == chartRepoName }) {
			"Unable to find repository to push the chart to in repository list (chartRepoName='$chartRepoName')"
		}

		ChartRepositoryDeploymentRequest(
			chartName,
			chartVersion,
			deployRepo.url,
			deployRepo.type,
			deployRepo.username,
			deployRepo.password
		)
	} else {
		ChartRegistryDeploymentRequest(
			chartName,
			chartVersion,
			chartRegistryUrl!!
		)
	}

	private fun executeDeploymentRequest(deploymentRequest: ChartDeploymentRequest) {
		when (deploymentRequest) {
			is ChartRepositoryDeploymentRequest -> {
				publishToRepo(deploymentRequest)
			}

			is ChartRegistryDeploymentRequest -> {
				val chartTgz = deploymentRequest.resolveChartTgzFile(project).absolutePath
				executeHelmCmd(listOf("push", chartTgz, deploymentRequest.remote))
			}
		}
	}


	private fun publishToRepo(chartDeploymentRequest: ChartRepositoryDeploymentRequest) {
		if (!chartFileExists(chartDeploymentRequest)) {
			log.info("Skip module ${chartDeploymentRequest.chartName}. There is no tar file present.")
			return
		}

		if (chartDeploymentRequest.chartVersion.endsWith("-SNAPSHOT")) {
			removeChartIfExists(chartDeploymentRequest)
		}

		publishChart(chartDeploymentRequest)
	}

	private fun chartFileExists(chartDeploymentRequest: ChartRepositoryDeploymentRequest): Boolean {
		val file = chartDeploymentRequest.resolveChartTgzFile(project)
		log.debug("Tar file location: ${file.absolutePath}.")
		return file.exists()
	}

	private fun removeChartIfExists(chartDeploymentRequest: ChartRepositoryDeploymentRequest) {
		val httpDelete = chartDeploymentRequest.chartDeleteUrl?.let { HttpDelete(it) } ?: return

		createChartRepoClient(chartDeploymentRequest).use { httpClient ->
			httpClient.execute(httpDelete).use { response ->
				if (response.statusLine.statusCode == 200) {
					log.info("Existing chart removed successfully")
				}
			}
		}
	}

	private fun createChartRepoClient(chartDeploymentRequest: ChartRepositoryDeploymentRequest) =
		HttpClientBuilder.create().apply {
			// cookie management is disabled to avoid parsing errors of the Set-Cookie header
			// see https://github.com/deviceinsight/helm-maven-plugin/issues/45
			disableCookieManagement()

			val httpUsername = chartDeploymentRequest.chartRepoUsername
			val httpPassword = chartDeploymentRequest.chartRepoPassword
			if (httpUsername != null && httpPassword != null) {
				setDefaultCredentialsProvider(BasicCredentialsProvider().apply {
					val credentials = UsernamePasswordCredentials(httpUsername, httpPassword)
					setCredentials(AuthScope.ANY, credentials)
				})
			}
		}.build()


	private fun publishChart(chartDeploymentRequest: ChartRepositoryDeploymentRequest) {
		val chartTarGzFile = chartDeploymentRequest.resolveChartTgzFile(project)
		log.debug(
			"Uploading $chartTarGzFile to " +
				"${chartDeploymentRequest.chartPublishUrl} using ${chartDeploymentRequest.chartPublishMethod}"
		)

		createChartRepoClient(chartDeploymentRequest).use { httpClient ->
			val request = RequestBuilder
				.create(chartDeploymentRequest.chartPublishMethod)
				.setUri(chartDeploymentRequest.chartPublishUrl)
				.setEntity(FileEntity(chartTarGzFile))
				.build()

			httpClient.execute(request).use { response ->
				val statusCode = response.statusLine.statusCode
				check(statusCode in 200..299) {
					"Unexpected status code when executing ${chartDeploymentRequest.chartPublishMethod} request to " +
						"chart repo ${chartDeploymentRequest.chartPublishUrl}: $statusCode"
				}
				log.info("$chartTarGzFile uploaded successfully")
			}
		}
	}
}

private sealed interface ChartDeploymentRequest {
	val chartName: String
	val chartVersion: String

	val chartTgzFileName: String
		get() = "$chartName-$chartVersion.tgz"

	fun resolveChartTgzFile(project: MavenProject) = File(project.build.directory)
		.resolve("helm")
		.resolve(chartTgzFileName)
}

private data class ChartRepositoryDeploymentRequest(
	override val chartName: String,
	override val chartVersion: String,
	val chartRepoUrl: String,
	val chartRepoType: Repo.Type,
	val chartRepoUsername: String?,
	val chartRepoPassword: String?,
) : ChartDeploymentRequest {
	val chartPublishUrl: String
	val chartPublishMethod: String
	val chartDeleteUrl: String?

	init {
		when (chartRepoType) {
			Repo.Type.CHARTMUSEUM -> {
				chartPublishMethod = "POST"
				chartPublishUrl = "$chartRepoUrl/api/charts"
				chartDeleteUrl = "$chartRepoUrl/api/charts/$chartName/$chartVersion"
			}

			Repo.Type.ARTIFACTORY -> {
				chartPublishMethod = "PUT"
				chartPublishUrl = "$chartRepoUrl/$chartTgzFileName"
				chartDeleteUrl = null
			}
		}
	}
}

private data class ChartRegistryDeploymentRequest(
	override val chartName: String,
	override val chartVersion: String,
	val remote: String,
) : ChartDeploymentRequest
