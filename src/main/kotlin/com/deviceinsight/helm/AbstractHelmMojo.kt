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

import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import java.io.File

abstract class AbstractHelmMojo : AbstractMojo() {

	@Parameter(property = "chartVersion", required = false, defaultValue = "\${project.model.version}")
	protected lateinit var chartVersion: String

	@Parameter(defaultValue = "\${project}", readonly = true, required = true)
	protected lateinit var project: MavenProject

	@Parameter(property = "chartFolder", required = false)
	private var chartFolder: String? = null

	/**
	 * Name of the chart
	 */
	@Parameter(property = "chartName", required = false)
	private var chartName: String? = null

	protected fun executeCmd(
		vararg cmd: String,
		directory: File = target(),
		redirectOutput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE
	) {
		val proc = ProcessBuilder(cmd.toList())
			.directory(directory)
			.redirectOutput(redirectOutput)
			.redirectError(ProcessBuilder.Redirect.PIPE)
			.start()

		proc.waitFor()

		log.debug("When executing '${cmd.toList()}' in '${directory.absolutePath}', result was ${proc.exitValue()}")
		proc.inputStream.bufferedReader().lines().forEach { log.debug("Output: $it") }
		proc.errorStream.bufferedReader().lines().forEach { log.error("Output: $it") }

		if (proc.exitValue() != 0) {
			throw RuntimeException("When executing '$cmd' got result code '${proc.exitValue()}'")
		}
	}


	protected fun target() = File(project.build.directory).resolve("helm")

	protected fun chartTarGzFile() = target().resolve("${chartName()}-${chartVersion}.tgz")

	protected fun chartName(): String = chartName ?: project.artifactId

	protected fun chartFolder() = chartFolder ?: "src/main/helm/${chartName()}"

	protected fun isChartFolderPresent() = File("${project.basedir}/${chartFolder()}").exists()

	protected fun quoteFilePath(filePath: String): String =
		if (filePath.contains(Regex("\\s"))) {
			"\"$filePath\""
		} else {
			filePath
		}

}
