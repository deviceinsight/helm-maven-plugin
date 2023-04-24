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

package com.deviceinsight.helm.model


class Registry {

	lateinit var url: String
	var serverId: String? = null
	var username: String? = null
	var password: String? = null

	fun validate() {
		require(this::url.isInitialized) { "Registry URL must be set" }

		if (username != null) {
			check(serverId == null) { "Registry username may not be set when serverId is used" }
		}

		if (password != null) {
			check(serverId == null) { "Registry password may not be set when serverId is used" }
		}

		if (serverId == null) {
			check(username != null) { "Please specify either username or serverId" }
			check(username != null) { "Please specify either password or serverId" }
		}
	}
}

fun List<Registry>.validate() = forEach(Registry::validate)
