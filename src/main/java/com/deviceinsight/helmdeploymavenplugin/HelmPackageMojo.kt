package com.deviceinsight.helmdeploymavenplugin

import org.apache.maven.artifact.repository.ArtifactRepository
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import org.apache.maven.repository.RepositorySystem
import java.io.File


/**
 * Packages helm charts
 */
abstract class AbstractPackageMojo : AbstractMojo() {

	companion object {
		private val placeholderRegex = Regex("\\$\\{(.*)}")
	}

	/**
	 * Name of the chart
	 */
	@Parameter(property = "chartName", required = false)
	private var chartName: String? = null

	@Parameter(property = "chartRepoUrl", required = true)
	private lateinit var chartRepoUrl: String

	@Parameter(property = "helmGroupId", defaultValue = "org.kubernetes.helm")
	private lateinit var helmGroupId: String

	@Parameter(property = "helmArtifactId", defaultValue = "helm-binary")
	private lateinit var helmArtifactId: String

	@Parameter(property = "helmVersion", required = true)
	private lateinit var helmVersion: String

	@Parameter(property = "chartFolder", required = false)
	private var chartFolder: String? = null

	@Parameter(defaultValue = "\${project}", readonly = true, required = true)
	private lateinit var project: MavenProject

	@Parameter(property = "helm.skip", defaultValue = "false")
	private var skip: Boolean = false

	@Parameter(readonly = true, required = true, defaultValue = "\${localRepository}")
	private lateinit var localRepository: ArtifactRepository

	@Parameter(readonly = true, required = true, defaultValue = "\${project.remoteArtifactRepositories}")
	private lateinit var remoteRepositories: List<ArtifactRepository>

	@Component
	private lateinit var repositorySystem: RepositorySystem


	@Throws(MojoExecutionException::class)
	override fun execute() {

		if (skip) {
			log.info("helm-package has been skipped")
			return
		}

		try {

			val targetHelmDir = File(target(), chartName())

			log.info("Clear target directory to ensure clean target package")
			if (targetHelmDir.exists()) {
				targetHelmDir.deleteRecursively()
			}
			targetHelmDir.mkdirs()
			log.info("Created target helm directory")

			processHelmConfigFiles(targetHelmDir)

			val helm = resolveHelmBinary()

			executeCmd("$helm init --client-only")
			executeCmd("$helm repo add incubator https://kubernetes-charts-incubator.storage.googleapis.com")
			executeCmd("$helm repo add chartRepo $chartRepoUrl")
			executeCmd("$helm dependency update", directory = targetHelmDir)
			executeCmd("$helm package ${chartName()} --version ${project.model.version}")

			ensureChartFileExists()

		} catch (e: Exception) {
			throw MojoExecutionException("Error creating helm chart: ${e.message}", e)
		}
	}

	private fun resolveHelmBinary(): String {

		val helmArtifact = repositorySystem.createArtifact(helmGroupId, helmArtifactId, helmVersion, "binary")

		val request = ArtifactResolutionRequest()
		request.artifact = helmArtifact
		request.isResolveTransitively = false
		request.localRepository = localRepository
		request.remoteRepositories = remoteRepositories

		val resolutionResult = repositorySystem.resolve(request)

		if (!resolutionResult.isSuccess) {
			throw RuntimeException("Unable to resolve maven artifact ${helmGroupId}:${helmArtifactId}:${helmVersion}")
		}

		helmArtifact.file.setExecutable(true)

		return helmArtifact.file.absolutePath
	}

	private fun ensureChartFileExists() {

		val chartTarGzFile = chartTarGzFile()

		if (!chartTarGzFile.exists()) {
			throw RuntimeException("File ${chartTarGzFile.absolutePath} not found. " +
					"Chart must be created in package phase first.")
		} else {
			log.info("Successfully packaged chart and saved it to: $chartTarGzFile")
		}
	}

	private fun processHelmConfigFiles(targetHelmDir: File) {
		val directory = File("${project.basedir}/${chartFolder()}")
		log.debug("Processing helm files in directory ${directory.absolutePath}")
		val processedFiles = directory.walkTopDown().filter { it.isFile }.onEach { file ->
			log.debug("Processing helm file ${file.absolutePath}")
			val targetFile = targetHelmDir.resolve(file.toRelativeString(directory))
			log.debug("Copying to ${targetFile.absolutePath}")
			targetFile.apply {
				parentFile.mkdirs()
			}

			targetFile.bufferedWriter().use { writer ->
				file.useLines { lines ->
					lines.map { line ->
						placeholderRegex.replace(line) { matchResult ->
							val property = matchResult.groupValues[1]
							val propertyValue = findPropertyValue(property)

							when (propertyValue) {
								null -> matchResult.groupValues[0]
								else -> propertyValue
							}
						}
					}.forEach {
						writer.appendln(it)
					}
				}
			}

		}.toList()

		if (processedFiles.isEmpty()) {
			throw IllegalStateException("No helm files found in ${directory.absolutePath}")
		}
	}

	private fun chartFolder() = chartFolder ?: "src/main/helm/${chartName()}"

	private fun executeCmd(cmd: String, directory: File = target()) {
		val proc = ProcessBuilder(cmd.split(" "))
				.directory(directory)
				.redirectOutput(ProcessBuilder.Redirect.PIPE)
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

	private fun findPropertyValue(property: String): CharSequence? {
		return when (property) {
			"project.version" -> project.version
			"artifactId" -> project.artifactId
			else -> project.properties.getProperty(property)
		}
	}

	private fun target() = File(project.build.directory).resolve("helm")

	private fun chartTarGzFile() = target().resolve("${chartName()}-${project.version}.tgz")

	private fun chartName() = chartName ?: project.artifactId

}

/**
 * define mojo for goal "helm-package"
 */
@Mojo(name = "helm-package", defaultPhase = LifecyclePhase.PACKAGE)
class HelmPackageMojo : AbstractPackageMojo()

/**
 * define mojo for goal "package"
 */
@Mojo(name = "package", defaultPhase = LifecyclePhase.PACKAGE)
class PackageMojo : AbstractPackageMojo()

