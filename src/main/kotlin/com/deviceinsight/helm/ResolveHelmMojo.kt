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
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.repository.RepositorySystem
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

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

	@Parameter(readonly = true, required = true, defaultValue = "\${localRepository}")
	private lateinit var localRepository: ArtifactRepository

	@Parameter(readonly = true, required = true, defaultValue = "\${project.remoteArtifactRepositories}")
	private lateinit var remoteRepositories: List<ArtifactRepository>

	@Component
	private lateinit var repositorySystem: RepositorySystem

	protected lateinit var helm: String

	override fun execute() {
		checkHelmVersion()
		helm = resolveHelmBinary()
	}

	private fun checkHelmVersion() {
		val (majorVersion, minorVersion) = helmVersion.split('.').map(String::toInt)

		if (majorVersion > 3) {
			log.warn("This plugin was not tested with versions beyond Helm 3")
			log.warn("Please check whether an update for this plugin is available")
		}

		require(majorVersion > 3 || (majorVersion == 3 && minorVersion >= 8)) {
			"This plugin requires at least Helm 3.8"
		}
	}

	private fun resolveHelmBinary(): String {
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
			localRepository = this@ResolveHelmMojo.localRepository
			remoteRepositories = this@ResolveHelmMojo.remoteRepositories
		}

		val resolutionResult = repositorySystem.resolve(request)
		if (!resolutionResult.isSuccess) {
			log.info("Artifact not found in remote repositories")
			downloadAndInstallHelm(helmArtifact, platformIdentifier)
		}

		return helmArtifact.file.apply { setExecutable(true) }.absolutePath
	}

	private fun downloadAndInstallHelm(artifact: Artifact, platformIdentifier: String) {
		val fileName = "helm-v$helmVersion-$platformIdentifier"
		val url = helmDownloadUrl.resolve("./$fileName.zip").toURL()
		val targetFile = artifact.file.toPath()

		Files.createDirectories(targetFile.parent)
		downloadFileAndExtractBinary(url, targetFile)
	}

	private fun downloadFileAndExtractBinary(url: URL, destination: Path) {
		log.info("Downloading Helm binary from $url")

		ZipInputStream(url.openStream()).use { zipInputStream ->
			val zipEntries = generateSequence { zipInputStream.nextEntry }
			val foundHelmBinaries = zipEntries.filter(::isHelmBinary).map {
				Files.copy(zipInputStream, destination, StandardCopyOption.REPLACE_EXISTING)
			}.count()

			require(foundHelmBinaries == 1) {
				"Expected 1 but found $foundHelmBinaries Helm binaries in $url"
			}
		}

		log.info("Finished downloading Helm binary")
	}

	private fun isHelmBinary(entry: ZipEntry): Boolean =
		!entry.isDirectory && (entry.name.endsWith("helm") || entry.name.endsWith("helm.exe"))
}
