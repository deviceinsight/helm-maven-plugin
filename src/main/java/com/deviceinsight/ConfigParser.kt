package com.deviceinsight

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

class ConfigParser {

	private val objectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

	fun parse(yaml: String): RewriteRuleConfig = objectMapper.readValue(yaml, RewriteRuleConfig::class.java)

}
