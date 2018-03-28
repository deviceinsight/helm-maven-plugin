package com.deviceinsight

import java.io.File

private val SNIPPET_FILENAMES = listOf(
		"httpie-request.adoc",
		"path-parameters.adoc",
		"request-parameters.adoc",
		"response-fields.adoc",
		"request-headers.adoc",
		"http-response.adoc",
		"curl-request.adoc",
		"http-request.adoc",
		"response-headers.adoc",
		"request-fields.adoc")

fun isSnippet(file: File) = file.name in SNIPPET_FILENAMES
