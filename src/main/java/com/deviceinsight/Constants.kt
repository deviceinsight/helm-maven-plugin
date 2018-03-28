package com.deviceinsight

val METHODS = "GET|PUT|POST|PATCH|DELETE"
val URL_PATTERN = Regex("($METHODS) (.*) (HTTP/.*)")
