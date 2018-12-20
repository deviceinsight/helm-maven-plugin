package com.deviceinsight.helmdeploymavenplugin

import org.apache.maven.artifact.repository.ArtifactRepository
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest
import org.apache.maven.artifact.resolver.ArtifactResolutionResult
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import org.apache.maven.repository.RepositorySystem
import java.io.File

abstract class AbstractHelmMojo : AbstractMojo() {

	@Parameter(property = "helmGroupId", defaultValue = "org.kubernetes.helm")
	private lateinit var helmGroupId: String

	@Parameter(property = "helmArtifactId", defaultValue = "helm-binary")
	private lateinit var helmArtifactId: String

	@Parameter(property = "helmVersion", required = true)
	private lateinit var helmVersion: String

	@Parameter(defaultValue = "\${project}", readonly = true, required = true)
	protected lateinit var project: MavenProject

	@Parameter(readonly = true, required = true, defaultValue = "\${localRepository}")
	private lateinit var localRepository: ArtifactRepository

	@Parameter(readonly = true, required = true, defaultValue = "\${project.remoteArtifactRepositories}")
	private lateinit var remoteRepositories: List<ArtifactRepository>

	@Component
	private lateinit var repositorySystem: RepositorySystem
	/**
	 * Name of the chart
	 */
	@Parameter(property = "chartName", required = false)
	private var chartName: String? = null

	protected fun resolveHelmBinary(): String {

		val helmArtifact = repositorySystem.createArtifact(helmGroupId, helmArtifactId, helmVersion, "binary")

		val request = ArtifactResolutionRequest()
		request.artifact = helmArtifact
		request.isResolveTransitively = false
		request.localRepository = localRepository
		request.remoteRepositories = remoteRepositories

		val resolutionResult: ArtifactResolutionResult = repositorySystem.resolve(request)

		if (!resolutionResult.isSuccess) {
			throw RuntimeException("Unable to resolve maven artifact ${helmGroupId}:${helmArtifactId}:${helmVersion}")
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

	protected fun target() = File(project.build.directory).resolve("helm")

	protected fun chartTarGzFile() = target().resolve("${chartName()}-${project.version}.tgz")

	protected fun chartName(): String = chartName ?: project.artifactId

}
