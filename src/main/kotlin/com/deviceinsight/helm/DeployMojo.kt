/*
 * Copyright 2018-2019 the original author or authors.
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
import org.apache.http.client.methods.HttpPost
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

/**
 * Publishes helm charts
 */
@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY)
class DeployMojo : AbstractMojo() {

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

	@Parameter(property = "skipSnapshots", required = false, defaultValue = "true")
	private var skipSnapshots: Boolean = true

	@Parameter(defaultValue = "\${project}", readonly = true, required = true)
	private lateinit var project: MavenProject

	@Parameter(property = "helm.skip", defaultValue = "false")
	private var skip: Boolean = false

	@Throws(MojoExecutionException::class)
	override fun execute() {

		if (skip) {
			log.info("helm-deploy has been skipped")
			return
		}

		try {

			if (skipSnapshots && isSnapshotVersion()) {
				log.info("Version contains SNAPSHOT and 'skipSnapshots' option is enabled. Not publishing.")
				return
			}

			publishToRepo()
		} catch (e: Exception) {
			throw MojoExecutionException("Error creating/publishing helm chart: ${e.message}", e)
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
			throw RuntimeException("File ${chartTarGzFile.absolutePath} not found. " +
				"Chart must be created in package phase first.")
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

	private fun createChartRepoClient(): CloseableHttpClient {
		val clientBuilder = HttpClientBuilder.create()

		if (chartRepoUsername != null && chartRepoPassword != null) {
			clientBuilder.setDefaultCredentialsProvider(BasicCredentialsProvider().apply {
				setCredentials(
					AuthScope.ANY,
					UsernamePasswordCredentials(chartRepoUsername, chartRepoPassword)
				)
			})
		}

		return clientBuilder.build()

	}

	private fun chartTarGzFile() = target().resolve("${chartName()}-${project.version}.tgz")

	private fun target() = File(project.build.directory).resolve("helm")

	private fun chartName() = chartName ?: project.artifactId

	private fun isSnapshotVersion() = project.version.contains("SNAPSHOT")
}
