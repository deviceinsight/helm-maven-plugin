package com.deviceinsight

data class Route(val method: String, val url: String, val authorities: List<String> = emptyList())
