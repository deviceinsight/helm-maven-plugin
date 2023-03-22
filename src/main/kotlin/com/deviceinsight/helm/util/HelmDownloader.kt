/*
 * Copyright 2023 the original author or authors.
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

package com.deviceinsight.helm.util

import org.apache.maven.plugin.logging.Log
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream


class HelmDownloader(
	private val log: Log
) {
	fun downloadAndInstallHelm(targetFile: File, helmVersion: String, helmDownloadUri: URI) {
		val platformIdentifier = PlatformDetector.detectHelmReleasePlatformIdentifier()
		val fileName = "helm-v$helmVersion-$platformIdentifier"
		val url = helmDownloadUri.resolve("./$fileName.zip").toURL()
		val targetFilePath = targetFile.toPath()

		Files.createDirectories(targetFilePath.parent)
		downloadFileAndExtractBinary(url, targetFilePath)
		targetFile.setExecutable(true)
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
