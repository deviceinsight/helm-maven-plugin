package com.deviceinsight.helm.util

import org.apache.maven.plugin.logging.Log
import org.apache.maven.settings.Server
import org.apache.maven.settings.Settings
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcherException

interface ServerAuthentication {

	val settings: Settings
	val securityDispatcher: SecDispatcher

	fun getLog(): Log

	fun getServer(chartRepoServerId: String?): Server? {
		if (chartRepoServerId != null) {
			val server = settings.getServer(chartRepoServerId)

			if (server != null) {
				return server
			}

			getLog().warn("No server definition found for $chartRepoServerId in the maven settings.xml server list.")
		}

		return null
	}

	@Throws(SecDispatcherException::class)
	fun decryptPassword(password: String?): String? {
		return if (password != null) securityDispatcher.decrypt(password) else null
	}

}
