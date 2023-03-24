def logLines = new File(basedir, "build.log").readLines()

assert logLines.contains("[WARNING] No sources found skipping helm package.")
assert logLines.contains("[WARNING] No sources found skipping helm lint.")
