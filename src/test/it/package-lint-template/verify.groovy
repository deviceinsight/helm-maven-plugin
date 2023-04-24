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

def logLines = new File(basedir, "build.log").readLines()

// Package goal
assert logLines.any { it.contains("Successfully packaged chart and saved it to") }

// Lint goal
assert logLines.any { it.contains("Chart.yaml: icon is recommended") }
assert logLines.any { it.contains("1 chart(s) linted, 0 chart(s) failed") }

// Template goal
def templatedChart = new File(basedir, "target/test-classes/helm.yaml")
assert templatedChart.exists()
assert templatedChart.readLines().contains("# Source: test-chart/templates/service.yaml")
