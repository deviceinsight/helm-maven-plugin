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
