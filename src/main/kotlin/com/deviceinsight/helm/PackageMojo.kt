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

import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import java.io.File

/**
 * Packages helm charts
 */
@Mojo(name = "package", defaultPhase = LifecyclePhase.PACKAGE)
class PackageMojo : ResolveHelmMojo() {

	companion object {
		private val PLACEHOLDER_REGEX = Regex("""\$\{(.*?)}""")
		private val SUBSTITUTED_EXTENSIONS = setOf("json", "tpl", "yml", "yaml")
	}

	@Parameter(property = "chartRepoUrl", required = false)
	private var chartRepoUrl: String? = null

	@Parameter(property = "helm.skip", defaultValue = "false")
	private var skip: Boolean = false

	@Parameter(property = "chartRepoUsername", required = false)
	private var chartRepoUsername: String? = null

	@Parameter(property = "chartRepoPassword", required = false)
	private var chartRepoPassword: String? = null

	@Parameter(property = "incubatorRepoUrl", defaultValue = "https://charts.helm.sh/incubator")
	private var incubatorRepoUrl: String = "https://charts.helm.sh/incubator"

	@Parameter(property = "addIncubatorRepo", defaultValue = "true")
	private var addIncubatorRepo: Boolean = true

	@Throws(MojoExecutionException::class)
	override fun execute() {

		if (skip) {
			log.info("helm-package has been skipped")
			return
		}

		try {

			if (!isChartFolderPresent()) {
				log.warn("No sources found skipping helm package.")
				return
			}

			super.execute()

			val targetHelmDir = File(target(), chartName())

			log.info("Clear target directory to ensure clean target package")
			if (targetHelmDir.exists()) {
				targetHelmDir.deleteRecursively()
			}
			targetHelmDir.mkdirs()
			log.info("Created target helm directory")

			processHelmConfigFiles(targetHelmDir)

			if (majorHelmVersion() < 3) {
				executeCmd("$helm init --client-only")
			}
			if (addIncubatorRepo) {
				executeCmd("$helm repo add incubator $incubatorRepoUrl")
			}
			if (chartRepoUrl != null) {
				val authParams = if (chartRepoUsername != null && chartRepoPassword != null) {
					" --username $chartRepoUsername --password $chartRepoPassword"
				} else {
					""
				}
				executeCmd("$helm repo add chartRepo $chartRepoUrl$authParams")
			}
			executeCmd("$helm dependency update", directory = targetHelmDir)
			executeCmd("$helm package ${chartName()} --version $chartVersion")

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

			if (!SUBSTITUTED_EXTENSIONS.contains(file.extension.toLowerCase())) {
				file.copyTo(targetFile, true)
				return@onEach
			}

			targetFile.bufferedWriter().use { writer ->
				file.useLines { lines ->
					lines.map { line ->
						PLACEHOLDER_REGEX.replace(line) { matchResult ->
							val property = matchResult.groupValues[1]

							when (val propertyValue = findPropertyValue(property, targetFile.absolutePath)) {
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

	private fun findPropertyValue(property: String, fileName: String): CharSequence? {
		val result = when (property) {
			"project.version" -> project.version
			"artifactId" -> project.artifactId
			"project.name" -> project.name
			in System.getProperties().keys -> System.getProperty(property)
			else -> project.properties.getProperty(property)
		}
		if (result == null) {
			log.warn("Could not resolve property: '$property' used in file: '$fileName'")
		} else {
			log.debug("Resolved property: '$property' as: '$result'")
		}
		return result
	}

}
