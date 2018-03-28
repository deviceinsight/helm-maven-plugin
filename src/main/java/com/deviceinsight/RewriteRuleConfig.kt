package com.deviceinsight

data class RewriteRuleConfig(val rules: List<RewriteRule>)

data class RewriteRule(val from: String, val to: String)
