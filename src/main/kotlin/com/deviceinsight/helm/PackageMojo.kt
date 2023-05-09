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

import com.deviceinsight.helm.util.ServerAuthentication
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import java.io.File
import java.io.IOException


/**
 * Packages helm charts
 */
@Mojo(name = "package", defaultPhase = LifecyclePhase.PACKAGE)
class PackageMojo : AbstractHelmMojo(), ServerAuthentication {

	companion object {
		private val PLACEHOLDER_REGEX = Regex("""(?<!\\)\$\{(.*?)}""")
		private val SUBSTITUTED_EXTENSIONS = setOf("json", "tpl", "yml", "yaml")
	}

	@Parameter(property = "extraValuesFiles")
	private val extraValuesFiles: List<String> = emptyList()

	override fun runMojo() {
		try {

			if (!isChartFolderPresent()) {
				log.warn("No sources found skipping helm package.")
				return
			}

			val targetHelmDir = File(target(), chartName)

			log.info("Clear target directory to ensure clean target package")
			if (targetHelmDir.exists()) {
				targetHelmDir.deleteRecursively()
			}
			targetHelmDir.mkdirs()
			log.info("Created target helm directory")

			processHelmConfigFiles(targetHelmDir)
			mergeValuesFiles(targetHelmDir, extraValuesFiles)

			validateAndAddRepos()
			validateAndAddRegistries()
			executeHelmCmd(listOf("dependency", "update"), directory = targetHelmDir)
			executeHelmCmd(listOf("package", chartName, "--version", chartVersion))

			ensureChartFileExists()

		} catch (e: Exception) {
			throw MojoExecutionException("Error creating helm chart: ${e.message}", e)
		}
	}

	private fun ensureChartFileExists() {
		val chartTarGzFile = chartTarGzFile()

		check(chartTarGzFile.exists()) {
			"File ${chartTarGzFile.absolutePath} not found. Chart must be created in package phase first."
		}

		log.info("Successfully packaged chart and saved it to: $chartTarGzFile")
	}

	private fun processHelmConfigFiles(targetHelmDir: File) {
		val directory = File("${project.basedir}/$chartFolder")
		log.debug("Processing helm files in directory ${directory.absolutePath}")
		val processedFiles = directory.walkTopDown().filter { it.isFile }.onEach { file ->
			log.debug("Processing helm file ${file.absolutePath}")
			val targetFile = targetHelmDir.resolve(file.toRelativeString(directory))
			log.debug("Copying to ${targetFile.absolutePath}")
			targetFile.apply {
				parentFile.mkdirs()
			}

			if (!SUBSTITUTED_EXTENSIONS.contains(file.extension.lowercase())) {
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
						writer.appendLine(it)
					}
				}
			}

		}.toList()

		if (processedFiles.isEmpty()) {
			throw IllegalStateException("No helm files found in ${directory.absolutePath}")
		}
	}

	private fun mergeValuesFiles(targetHelmDir: File, extraValuesFiles: List<String>) {

		if (extraValuesFiles.isEmpty()) {
			return
		}

		val missingFiles = extraValuesFiles.filter { !File(it).exists() }
		if (missingFiles.isNotEmpty()) {
			throw IllegalStateException("extraValueFiles not found: $missingFiles")
		}

		val allValuesFiles = extraValuesFiles.toMutableList()
		val valuesFile = targetHelmDir.resolve("values.yaml")

		if (valuesFile.exists()) {
			allValuesFiles.add(0, valuesFile.absolutePath)
		}

		val yamlMapper = ObjectMapper(YAMLFactory().enable(YAMLGenerator.Feature.MINIMIZE_QUOTES))
		val values: ObjectNode = yamlMapper.createObjectNode()
		val yamlReader = yamlMapper.setDefaultMergeable(true).readerForUpdating(values)

		try {
			for (file in allValuesFiles) {
				yamlReader.readValue(File(file), ObjectNode::class.java)
			}
			yamlMapper.writeValue(valuesFile, values)
		} catch (e: IOException) {
			throw RuntimeException(e)
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
