package com.deviceinsight.helmdeploymavenplugin

import java.util.Locale

object PlatformDetector {

	private val IGNORED_CHARACTERS = Regex("[^a-z0-9_]")

	fun detectHelmReleasePlatformIdentifier(): String {
		val os: String = determineOperatingSystem()
		val arch: String = determinePlatformArchitecture()
		return "$os-$arch"
	}

	private fun determineOperatingSystem(): String {
		val osName: String = normalizeIdentifier(System.getProperty("os.name"))
		return when {
			osName.contains("windows") -> "windows"
			osName.contains("mac") || osName.contains("osx") -> "darwin"
			osName.contains("linux") -> "linux"
			else -> throw IllegalStateException("Unsupported OS '${System.getProperty("os.name")}'")
		}
	}

	private fun determinePlatformArchitecture(): String {
		val osArch: String = normalizeIdentifier(System.getProperty("os.arch"))
		return when {
			osArch == "x86_64" || osArch == "amd64" -> "amd64"
			osArch == "x86" || osArch == "i386" -> "386"
			osArch == "aarch32" || osArch.startsWith("arm") -> "arm"
			osArch.contains("arm64") -> "arm64"
			osArch.contains("ppc64le") ||
					(osArch.contains("ppc64") && System.getProperty("sun.cpu.endian") == "little") -> "ppc64le"
			else -> throw IllegalStateException("Unsupported platform architecture '${System.getProperty("os.arch")}'")
		}
	}

	private fun normalizeIdentifier(identifier: String): String {
		return identifier.toLowerCase(Locale.US).replace(IGNORED_CHARACTERS, "")
	}

}
