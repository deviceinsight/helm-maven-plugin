package com.deviceinsight


import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import java.io.File

/**
 * Packages helm charts
 */
// TODO: Change to deploy?
@Mojo(name = "helm-package", defaultPhase = LifecyclePhase.PACKAGE)
class HelmPackageMojo : AbstractMojo() {

	/**
	 * Name of the chart
	 */
	@Parameter(property = "chartName", required = true)
	private lateinit var chartName: String

	@Parameter(defaultValue = "\${project}", readonly = true, required = true)
	private lateinit var project: MavenProject

	@Throws(MojoExecutionException::class)
	override fun execute() {



		try {

			val targetHelmDir = File(target(), chartName)
			targetHelmDir.mkdirs()

			project.properties.forEach { log.info("!!!!!!!!!!${it.key} ${it.value}") }

			File("src/main/helm").walkTopDown().filter { it.isFile }.forEach { file ->
				val fileContents = file.readText()
				val updatedFileContents = Regex("\\$\\{(.*)}").replace(fileContents) { matchResult ->
					val property = matchResult.groupValues[1]
					val propertyValue = findPropertyValue(property)

					when(propertyValue) {
						null -> matchResult.groupValues[0] + "!!!"
						else -> propertyValue
					}
				}

				targetHelmDir.resolve(file.name).writeText(updatedFileContents)
			}

			executeCmd("helm dependency update", directory = targetHelmDir)
			executeCmd("helm package $chartName")


		} catch (e: Exception) {
			throw MojoExecutionException("Error creating helm chart: ${e.message}", e)
		}
	}

	private fun executeCmd(cmd: String, directory: File = target()) {
		val proc = ProcessBuilder(cmd.split(" "))
				.directory(directory)
				.redirectOutput(ProcessBuilder.Redirect.INHERIT)
				.redirectError(ProcessBuilder.Redirect.INHERIT)
				.start()

		proc.waitFor()

		log.info("Result was ${proc.exitValue()}")
	}

	private fun artifactId() = project.artifactId

	private fun target() = File(project.build.outputDirectory)

	private fun findPropertyValue(property: String): CharSequence? {
		if (property == "project.version") {
			return project.version
		}

		return project.properties.getProperty(property)
	}
}
