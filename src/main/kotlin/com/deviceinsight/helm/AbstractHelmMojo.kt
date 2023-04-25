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

import com.deviceinsight.helm.model.Registry
import com.deviceinsight.helm.model.Repo
import com.deviceinsight.helm.model.validate
import com.deviceinsight.helm.util.HelmDownloader
import com.deviceinsight.helm.util.PlatformDetector
import com.deviceinsight.helm.util.ServerAuthentication
import org.apache.maven.artifact.repository.ArtifactRepository
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import org.apache.maven.repository.RepositorySystem
import org.apache.maven.settings.Settings
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher
import java.io.File
import java.net.URI
import kotlin.concurrent.thread

abstract class AbstractHelmMojo : AbstractMojo(), ServerAuthentication {
	@Parameter(property = "skip", defaultValue = "false")
	private var skip: Boolean = false

	@Parameter(property = "chartVersion", required = true, defaultValue = "\${project.model.version}")
	protected lateinit var chartVersion: String

	@Parameter(property = "chartName", required = true, defaultValue = "\${project.artifactId}")
	protected lateinit var chartName: String

	@Parameter(property = "chartFolder", required = true, defaultValue = "src/main/helm/\${chartName}")
	protected lateinit var chartFolder: String

	@Parameter(property = "helmVersion", required = true, defaultValue = "3.11.3")
	private lateinit var helmVersion: String

	@Parameter(property = "helmDownloadUrl", required = true, defaultValue = "https://get.helm.sh/")
	private lateinit var helmDownloadUrl: URI

	@Parameter(property = "repos")
	protected var repos: List<Repo> = emptyList()

	@Parameter(property = "registries")
	protected var registries: List<Registry> = emptyList()

	@Parameter(readonly = true, required = true, defaultValue = "\${localRepository}")
	private lateinit var localRepository: ArtifactRepository

	@Parameter(readonly = true, required = true, defaultValue = "\${project.remoteArtifactRepositories}")
	private lateinit var remoteRepositories: List<ArtifactRepository>

	@Component(role = SecDispatcher::class, hint = "default")
	override lateinit var securityDispatcher: SecDispatcher

	@Parameter(defaultValue = "\${settings}", readonly = true)
	override lateinit var settings: Settings

	@Parameter(defaultValue = "\${project}", readonly = true)
	protected lateinit var project: MavenProject

	@Component
	private lateinit var repositorySystem: RepositorySystem

	private val helmDownloader = HelmDownloader(log)
	private lateinit var helm: String

	final override fun execute() {
		if (skip) {
			log.info("execution has been skipped")
			return
		}

		runMojo()
	}

	abstract fun runMojo()

	protected fun validateAndAddRepos() {
		repos.validate()

		repos.forEach { repo ->
			getServer(repo.serverId)?.let { server ->
				repo.username = server.username
				repo.password = server.password
			}
		}

		repos.forEach {
			log.debug("Adding chart repo ${it.name} with url ${it.url}")

			val cmd = mutableListOf("repo", "add", it.name, it.url)
			val username = it.username
			val password = it.password
			if (username != null && password != null) {
				cmd += listOf("--username", username, "--password-stdin")
			}
			if (it.passCredentials) cmd += "--pass-credentials"
			if (it.forceUpdate) cmd += "--force-update"

			executeHelmCmd(cmd, stdinData = password?.toByteArray())
		}
	}

	protected fun validateAndAddRegistries() {
		registries.validate()

		registries.forEach { registry ->
			getServer(registry.serverId)?.let { server ->
				registry.username = server.username
				registry.password = server.password
			}
		}

		registries.forEach {
			log.debug("Adding chart registry ${it.url}")

			val hostWithPort = with(URI(it.url)) {
				host + if (port != -1) ":${port}" else ""
			}
			val cmd = listOf(
				"registry", "login", hostWithPort,
				"--username", it.username!!,
				"--password-stdin"
			)

			executeHelmCmd(cmd, stdinData = it.password!!.toByteArray())
		}
	}

	protected fun executeHelmCmd(
		cmd: List<String>,
		directory: File = target(),
		logStdoutToInfo: Boolean = false,
		redirectOutput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE,
		stdinData: ByteArray? = null
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

		if (stdinData != null) {
			proc.outputStream.use { it.write(stdinData) }
		}

		proc.waitFor()
		stdoutPrinter.join()
		stderrPrinter.join()

		log.debug("When executing 'helm ${cmd.joinToString(" ")}' in '${directory.absolutePath}', result was ${proc.exitValue()}")

		if (proc.exitValue() != 0) {
			throw RuntimeException("When executing 'helm ${cmd.joinToString(" ")}' got result code '${proc.exitValue()}'")
		}
	}

	private fun initializeHelm() {
		if (!::helm.isInitialized) {
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
			"com.deviceinsight.helm",
			"helm",
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

	protected fun chartTarGzFile() = target().resolve("$chartName-$chartVersion.tgz")

	protected fun isChartFolderPresent() = File("${project.basedir}/$chartFolder").exists()
}
