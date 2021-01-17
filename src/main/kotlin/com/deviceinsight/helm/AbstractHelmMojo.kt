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

import com.deviceinsight.helm.util.PlatformDetector
import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.repository.ArtifactRepository
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest
import org.apache.maven.artifact.resolver.ArtifactResolutionResult
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import org.apache.maven.repository.RepositorySystem
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.system.measureTimeMillis

abstract class AbstractHelmMojo : AbstractMojo() {

	@Parameter(property = "helmGroupId", defaultValue = "com.deviceinsight.helm")
	private lateinit var helmGroupId: String

	@Parameter(property = "helmArtifactId", defaultValue = "helm")
	private lateinit var helmArtifactId: String

	@Parameter(property = "helmVersion", required = true)
	private lateinit var helmVersion: String

	@Parameter(property = "helmDownloadUrl", defaultValue = "https://get.helm.sh/")
	private lateinit var helmDownloadUrl: URI

	@Parameter(property = "chartVersion", required = false, defaultValue = "\${project.model.version}")
	protected lateinit var chartVersion: String

	@Parameter(defaultValue = "\${project}", readonly = true, required = true)
	protected lateinit var project: MavenProject

	@Parameter(readonly = true, required = true, defaultValue = "\${localRepository}")
	private lateinit var localRepository: ArtifactRepository

	@Parameter(readonly = true, required = true, defaultValue = "\${project.remoteArtifactRepositories}")
	private lateinit var remoteRepositories: List<ArtifactRepository>

	@Component
	private lateinit var repositorySystem: RepositorySystem

	@Parameter(property = "chartFolder", required = false)
	private var chartFolder: String? = null

	/**
	 * Name of the chart
	 */
	@Parameter(property = "chartName", required = false)
	private var chartName: String? = null

	protected fun resolveHelmBinary(): String {

		val platformIdentifier = PlatformDetector.detectHelmReleasePlatformIdentifier()
		val helmArtifact: Artifact =
			repositorySystem.createArtifactWithClassifier(helmGroupId, helmArtifactId, helmVersion, "binary",
				platformIdentifier)

		val request = ArtifactResolutionRequest()
		request.artifact = helmArtifact
		request.isResolveTransitively = false
		request.localRepository = localRepository
		request.remoteRepositories = remoteRepositories

		val resolutionResult: ArtifactResolutionResult = repositorySystem.resolve(request)

		if (!resolutionResult.isSuccess) {
			log.info("Artifact not found in remote repositories")
			downloadAndInstallHelm(helmArtifact, platformIdentifier)
		}

		helmArtifact.file.setExecutable(true)

		return helmArtifact.file.absolutePath
	}

	protected fun executeCmd(cmd: String, directory: File = target(),
							 redirectOutput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE) {
		val proc = ProcessBuilder(cmd.split(" "))
			.directory(directory)
			.redirectOutput(redirectOutput)
			.redirectError(ProcessBuilder.Redirect.PIPE)
			.start()

		proc.waitFor()

		log.debug("When executing '$cmd' in '${directory.absolutePath}', result was ${proc.exitValue()}")
		proc.inputStream.bufferedReader().lines().forEach { log.debug("Output: $it") }
		proc.errorStream.bufferedReader().lines().forEach { log.error("Output: $it") }

		if (proc.exitValue() != 0) {
			throw RuntimeException("When executing '$cmd' got result code '${proc.exitValue()}'")
		}
	}

	protected fun majorHelmVersion(): Int = helmVersion.splitToSequence('.').first().toInt()

	protected fun target() = File(project.build.directory).resolve("helm")

	protected fun chartTarGzFile() = target().resolve("${chartName()}-${chartVersion}.tgz")

	protected fun chartName(): String = chartName ?: project.artifactId

	protected fun chartFolder() = chartFolder ?: "src/main/helm/${chartName()}"

	protected fun isChartFolderPresent() = File("${project.basedir}/${chartFolder()}").exists()

	private fun downloadAndInstallHelm(artifact: Artifact, platformIdentifier: String) {

		val fileName = "helm-v$helmVersion-$platformIdentifier"

		val targetFile = artifact.file.toPath()
		Files.createDirectories(targetFile.parent)

		val url = helmDownloadUrl.resolve("./$fileName.zip").toURL()

		downloadFileAndExtractBinary(url, targetFile)
	}

	private fun downloadFileAndExtractBinary(url: URL, destination: Path) {
		val httpConnection = url.openConnection()
		httpConnection.connect()
		if (httpConnection !is HttpURLConnection || httpConnection.responseCode != 200) {
			throw RuntimeException("Could not download file from $url")
		}

		val sizeInMiB: Double = httpConnection.contentLengthLong / 1024.0 / 1024.0
		log.info("Downloading $url; need to get %.1f MiB...".format(sizeInMiB))

		val downloadTimeMillis = measureTimeMillis {
			httpConnection.inputStream.use {
				ZipInputStream(it).use { zip ->
					var entry: ZipEntry? = zip.nextEntry
					do {
						if (entry != null) {
							if (isHelmBinary(entry)) {
								Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING)
								zip.closeEntry()
								break
							} else {
								zip.closeEntry()
								entry = zip.nextEntry
							}
						}
					} while (entry != null)
				}
			}
		}

		log.info("Download took %.1f seconds".format(downloadTimeMillis / 1000.0))
	}

	private fun isHelmBinary(entry: ZipEntry): Boolean =
		!entry.isDirectory && (entry.name.endsWith("helm") || entry.name.endsWith("helm.exe"))

	protected fun quoteFilePath(filePath: String): String =
		if (filePath.contains(Regex("\\s"))) {
			"\"$filePath\""
		} else {
			filePath
		}

}
