def logLines = new File(basedir, "build.log").readLines()

// Package goal
assert logLines.any { line -> line.contains("Successfully packaged chart and saved it to") }

// Lint goal
assert logLines.any { line -> line.contains("Chart.yaml: icon is recommended") }
assert logLines.any { line -> line.contains("1 chart(s) linted, 0 chart(s) failed") }

// Template goal
def templatedChart = new File(basedir, "target/test-classes/helm.yaml")
assert templatedChart.exists()
assert templatedChart.readLines().contains("# Source: test-chart/templates/service.yaml")
