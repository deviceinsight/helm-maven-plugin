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
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactRequest
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

@Mojo(name = "resolve", defaultPhase = LifecyclePhase.NONE)
open class ResolveHelmMojo : AbstractHelmMojo() {

	@Parameter(property = "helmGroupId", defaultValue = "com.deviceinsight.helm")
	private lateinit var helmGroupId: String

	@Parameter(property = "helmArtifactId", defaultValue = "helm")
	private lateinit var helmArtifactId: String

	@Parameter(property = "helmVersion", required = true)
	private lateinit var helmVersion: String

	@Parameter(property = "helmDownloadUrl", defaultValue = "https://get.helm.sh/")
	private lateinit var helmDownloadUrl: URI

	@Parameter(readonly = true, required = true, defaultValue = "\${repositorySystemSession}")
	private lateinit var repositorySession: RepositorySystemSession

	@Parameter(readonly = true, required = true, defaultValue = "\${project.remoteProjectRepositories}")
	private lateinit var remoteRepositories: List<RemoteRepository>

	@Component
	private lateinit var repositorySystem: RepositorySystem

	protected lateinit var helm: String

	protected fun resolveHelmBinary(): String {

		val platformIdentifier = PlatformDetector.detectHelmReleasePlatformIdentifier()
		val helmArtifact: Artifact = DefaultArtifact(helmGroupId, helmArtifactId, platformIdentifier, "binary", helmVersion)

		var resolvedArtifact = findArtifact(helmArtifact)

		if (resolvedArtifact == null) {
			log.info("Artifact not found in remote repositories")
			resolvedArtifact = downloadAndInstallHelm(helmArtifact, platformIdentifier)
		}

		resolvedArtifact.file.setExecutable(true)

		return resolvedArtifact.file.absolutePath
	}

	private fun findArtifact(helmArtifact: Artifact): Artifact? {

		try {
			val request = ArtifactRequest()
			request.setArtifact(helmArtifact)
			request.setRepositories(remoteRepositories)
			val resolutionResult = repositorySystem.resolveArtifact(repositorySession, request)

			return if (resolutionResult.isResolved) {
				resolutionResult.artifact
			} else {
				null
			}

		} catch (e: Exception) {
			log.debug("failed to resolve artifact: ${e.message}")
			return null
		}
	}

	private fun downloadAndInstallHelm(artifact: Artifact, platformIdentifier: String): Artifact {

		val fileName = "helm-v$helmVersion-$platformIdentifier"

		val artifactPath = repositorySession.localRepositoryManager.getPathForLocalArtifact(artifact)
		val targetFile = File(repositorySession.localRepository.basedir, artifactPath).toPath()
		Files.createDirectories(targetFile.parent)

		val url = helmDownloadUrl.resolve("./$fileName.zip").toURL()

		downloadFileAndExtractBinary(url, targetFile)
		return artifact.setFile(targetFile.toFile())
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

	protected fun majorHelmVersion(): Int = helmVersion.splitToSequence('.').first().toInt()


	private fun isHelmBinary(entry: ZipEntry): Boolean =
		!entry.isDirectory && (entry.name.endsWith("helm") || entry.name.endsWith("helm.exe"))

	override fun execute() {
		helm = resolveHelmBinary()
	}
}
