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

import com.deviceinsight.helm.util.HelmDownloader
import com.deviceinsight.helm.util.PlatformDetector
import org.apache.maven.artifact.repository.ArtifactRepository
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import org.apache.maven.repository.RepositorySystem
import java.io.File
import java.net.URI
import kotlin.concurrent.thread

abstract class AbstractHelmMojo : AbstractMojo() {

	@Parameter(property = "chartVersion", required = false, defaultValue = "\${project.model.version}")
	protected lateinit var chartVersion: String

	@Parameter(property = "chartFolder", required = false)
	private var chartFolder: String? = null

	@Parameter(property = "chartName", required = false)
	private var chartName: String? = null

	@Parameter(property = "helmGroupId", defaultValue = "com.deviceinsight.helm")
	private lateinit var helmGroupId: String

	@Parameter(property = "helmArtifactId", defaultValue = "helm")
	private lateinit var helmArtifactId: String

	@Parameter(property = "helmVersion", required = true, defaultValue = "3.11.2")
	private lateinit var helmVersion: String

	@Parameter(property = "helmDownloadUrl", defaultValue = "https://get.helm.sh/")
	private lateinit var helmDownloadUrl: URI

	@Parameter(readonly = true, required = true, defaultValue = "\${localRepository}")
	private lateinit var localRepository: ArtifactRepository

	@Parameter(readonly = true, required = true, defaultValue = "\${project.remoteArtifactRepositories}")
	private lateinit var remoteRepositories: List<ArtifactRepository>

	@Component
	protected lateinit var project: MavenProject

	@Component
	private lateinit var repositorySystem: RepositorySystem

	private val helmDownloader = HelmDownloader(log)
	private lateinit var helm: String

	protected fun executeHelmCmd(
		cmd: List<String>,
		directory: File = target(),
		logStdoutToInfo: Boolean = false,
		redirectOutput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE
	) {
		initializeHelm()

		check(directory.exists()) {
			val hint = if (directory == target()) {
				"Target helm chart dir ($directory) does not exist. Did you run the 'package' goal first?"
			} else {
				"Working directory does not exist: $directory"
			}

			"Unable to execute 'helm ${cmd.joinToString(" ")}': $hint"
		}

		val proc = ProcessBuilder(listOf(helm) + cmd)
			.directory(directory)
			.redirectOutput(redirectOutput)
			.redirectError(ProcessBuilder.Redirect.PIPE)
			.start()

		val stdoutPrinter = thread(name = "Stdout printer") {
			val logFunction: (String) -> Unit = if (logStdoutToInfo) log::info else log::debug
			proc.inputStream.bufferedReader().lines().forEach { logFunction("Output: $it") }
		}

		val stderrPrinter = thread(name = "Stderr printer") {
			proc.errorStream.bufferedReader().lines().forEach { log.error("Output: $it") }
		}

		proc.waitFor()
		stdoutPrinter.join()
		stderrPrinter.join()

		log.debug("When executing 'helm ${cmd.joinToString(" ")}' in '${directory.absolutePath}', result was ${proc.exitValue()}")

		if (proc.exitValue() != 0) {
			throw RuntimeException("When executing 'helm ${cmd.joinToString(" ")}' got result code '${proc.exitValue()}'")
		}
	}

	fun initializeHelm() {
		if(!this::helm.isInitialized) {
			checkHelmVersion()
			resolveHelmBinary()
		}
	}

	private fun checkHelmVersion() {
		val versionPattern = """^(\d+)\.(\d+)\..*""".toRegex()
		val matchResult = versionPattern.matchEntire(helmVersion)
		requireNotNull(matchResult) { "Expected Helm version '$helmVersion' to match '$versionPattern'" }
		val (majorVersion, minorVersion) = matchResult.destructured.toList().map(String::toInt)

		if (majorVersion > 3) {
			log.warn("This plugin was not tested with versions beyond Helm 3")
			log.warn("Please check whether an update for this plugin is available")
		}

		require(majorVersion > 3 || (majorVersion == 3 && minorVersion >= 8)) {
			"This plugin requires at least Helm 3.8"
		}
	}

	private fun resolveHelmBinary() {
		val platformIdentifier = PlatformDetector.detectHelmReleasePlatformIdentifier()
		val helmArtifact = repositorySystem.createArtifactWithClassifier(
			helmGroupId,
			helmArtifactId,
			helmVersion,
			"binary",
			platformIdentifier
		)
		val request = ArtifactResolutionRequest().apply {
			artifact = helmArtifact
			isResolveTransitively = false
			localRepository = this@AbstractHelmMojo.localRepository
			remoteRepositories = this@AbstractHelmMojo.remoteRepositories
		}

		val resolutionResult = repositorySystem.resolve(request)
		if (!resolutionResult.isSuccess) {
			log.info("Artifact not found in remote repositories")
			helmDownloader.downloadAndInstallHelm(helmArtifact.file, helmVersion, helmDownloadUrl)
		}

		helm = helmArtifact.file.absolutePath
	}


	protected fun target() = File(project.build.directory).resolve("helm")

	protected fun chartTarGzFile() = target().resolve("${chartName()}-${chartVersion}.tgz")

	protected fun chartName(): String = chartName ?: project.artifactId

	protected fun chartFolder() = chartFolder ?: "src/main/helm/${chartName()}"

	protected fun isChartFolderPresent() = File("${project.basedir}/${chartFolder()}").exists()
}
