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


@Mojo(name = "lint", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST)
class LintMojo : ResolveHelmMojo() {

	/**
	 * An optional values.yaml file that is used to run linting, relative to `${project.basedir}`.
	 */
	@Parameter(property = "valuesFile", required = false)
	private var valuesFile: String? = null

	@Parameter(property = "strictLint", required = false, defaultValue = "false")
	private var strictLint: Boolean = false

	@Parameter(property = "helm.skip", defaultValue = "false")
	private var skip: Boolean = false

	@Throws(MojoExecutionException::class)
	override fun execute() {

		if (skip) {
			log.info("helm-lint has been skipped")
			return
		}

		try {

			if (!isChartFolderPresent()) {
				log.warn("No sources found skipping helm lint.")
				return
			}

			super.execute()

			val command = mutableListOf(helm, "lint", chartName())

			if (strictLint) {
				command.add("--strict")
			}

			if (valuesFile != null) {
				command.add("--values")
				command.add(quoteFilePath(project.basedir.resolve(valuesFile!!).absolutePath))
			}

			executeCmd(command)

		} catch (e: Exception) {
			throw MojoExecutionException("Error rendering helm lint: ${e.message}", e)
		}
	}
}
