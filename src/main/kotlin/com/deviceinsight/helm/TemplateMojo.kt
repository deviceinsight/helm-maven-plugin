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

@Mojo(name = "template", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST)
class TemplateMojo : ResolveHelmMojo() {

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

			if (!isChartFolderPresent()) {
				log.warn("No sources found, skipping helm template.")
				return
			}

			super.execute()

			val command = if (valuesFile != null) {
				val valuesFilePath = project.basedir.resolve(valuesFile!!).absolutePath
				listOf(helm, "template", "--values", valuesFilePath, chartName())
			} else {
				listOf(helm, "template", chartName())
			}

			var file = File(outputFile)
			if (!file.isAbsolute) {
				file = project.basedir.resolve(file)
			}

			if (!file.parentFile.exists()) {
				file.parentFile.mkdirs()
			}

			executeCmd(command, redirectOutput = ProcessBuilder.Redirect.to(file))

			log.info("Rendered helm template to '${file.absolutePath}'")

		} catch (e: Exception) {
			throw MojoExecutionException("Error rendering helm templates: ${e.message}", e)
		}
	}
}
