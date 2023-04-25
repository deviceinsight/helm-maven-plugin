# Helm Maven Plugin

[![Java CI with Maven](https://github.com/deviceinsight/helm-maven-plugin/actions/workflows/maven.yml/badge.svg)](https://github.com/deviceinsight/helm-maven-plugin/actions/workflows/maven.yml)

A Maven plugin that makes Maven properties available in your Helm Charts. It can be used to automatically package your
Helm Chart, render it and upload it to a Helm repository like [ChartMuseum](https://chartmuseum.com/).

## Usage

Add the following to your `pom.xml`

```xml
<build>
  <plugins>
    ...
    <plugin>
      <groupId>com.deviceinsight.helm</groupId>
      <artifactId>helm-maven-plugin</artifactId>
      <version>3.0.0</version>
      <configuration>
        <chartName>my-chart</chartName>
        <chartRepoUrl>https://charts.helm.sh/stable</chartRepoUrl>
        <strictLint>true</strictLint>
        <valuesFile>src/test/helm/my-chart/values.yaml</valuesFile>
        <repos>
          <repo>
            <url>https://your-chart-museum-host.example/</url>
          </repo>
        </repos>
      </configuration>
      <executions>
        <execution>
          <goals>
            <goal>package</goal>
            <goal>lint</goal>
            <goal>template</goal>
            <goal>deploy</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

In the same Maven module, put your helm charts into `src/main/helm/<chartName>`. In the `Chart.yaml`, you can use the
`${artifactId}`, `${project.version}`, and `${project.name}` Maven placeholders. You can also use Maven properties.

An example for a `Chart.yaml` is:

```yaml
apiVersion: v1
description: A Helm chart installing Rubicon
name: ${artifactId}
version: ${project.version}
home: https://gitlab.device-insight.com/deviceinsight/rubicon
maintainers:
  - name: Device Insight
    url: https://www.device-insight.com
```

You probably also will adjust the `templates/deployment.yaml` so
that the correct docker image is used. An example snippet:

```yaml
#[...]
containers:
  - name: "{{ .Chart.Name }}"
    image: "{{ .Values.image.repository }}/rubicon:${project.version}"
#[...]
```

## Configuration

| Property                       | Default                         | Description                                                                                                                                                      |
|--------------------------------|:--------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| chartName                      | The Maven `artifactId`          | The name of the chart                                                                                                                                            |
| chartVersion                   | `${project.model.version}`      | The version of the chart                                                                                                                                         |
| chartFolder                    | `src/main/helm/<chartName>`     | The location of the chart files (e.g. Chart.yaml)                                                                                                                |
| helmVersion                    | `3.11.3`                        | The helm binary `version`                                                                                                                                        |
| helmDownloadUrl                | `https://get.helm.sh/`          | The URL where the helm binary is downloaded from                                                                                                                 |
| repos.repo[].name              | `chartRepo`                     | The name of the chart repo. You can refer to it via `@chartRepo` in the `requirements.yml` file                                                                  |
| repos.repo[].type              | `CHARTMUSEUM`                   | The expected repository layout of the chart repo. Can be `CHARTMUSEUM` or `ARTIFACTORY`. This is only relevant when you intend to push charts to this repository |
| repos.repo[].url               | None                            | The URL of the Chart repository where dependencies are required from and where charts should be published to                                                     |
| repos.repo[].serverId          | None                            | The maven server id which can be used to populate `username` and `password`                                                                                      |
| repos.repo[].username          | None                            | The username for basic authentication against the chart repo                                                                                                     |
| repos.repo[].password          | None                            | The password for basic authentication against the chart repo                                                                                                     |
| repos.repo[].passCredentials   | `false`                         | Whether to pass credentials to all domains                                                                                                                       |
| repos.repo[].forceUpdate       | `true`                          | Whether to replace (overwrite) the repo if it already exists                                                                                                     |
| registries.registry[].url      | None                            | The URL of the OCI chart registry in the format `oci://server-name.example/`                                                                                     |
| registries.registry[].serverId | None                            | The maven server id which can be used to populate `username` and `password`                                                                                      |
| registries.registry[].username | None                            | The username for authentication against the registry                                                                                                             |
| registries.registry[].password | None                            | The password for authentication against the registry                                                                                                             |
| chartRepoName                  | None                            | Name of the chart repo to deploy the chart to. The name is autodetected if only one repo is specified in the `repos` element                                     |
| chartRegistryUrl               | None                            | URL of the OCI chat registry to deploy the chart to. The URL is autodetected if only one registry is specified in the `registries` element                       |
| skipSnapshots                  | `true`                          | If true, SNAPSHOT versions will be built, but not deployed                                                                                                       |
| skip                           | `false`                         | If true, execution will be skipped entirely                                                                                                                      |
| strictLint                     | `false`                         | If true, linting fails on warnings (see: [Lint](#lint))                                                                                                          |
| valuesFile                     | None                            | Values file that should be used for goals [Lint](#lint), [Template](#template)                                                                                   |
| extraValuesFiles               | None                            | A list of additional values files that can be generated dynamically and will be merged with the values.yaml during [Package](#package)                           |
| outputFile                     | `target/test-classes/helm.yaml` | Output file for [template goal](#template)                                                                                                                       |
| deployAtEnd                    | `false`                         | If true, the helm chart is deployed at the end of a multi-module Maven build. This option does not make sense for single-module Maven projects                   |

## Goals

### Package

Goal packages a chart directory into a chart archive using the [helm package](https://github.com/helm/helm/blob/master/docs/helm/helm_package.md) command.

### Deploy

Goal publishes the packaged chart in the configured chart repository.

### Lint

Goal examines a chart for possible issues using the [helm lint](https://github.com/helm/helm/blob/master/docs/helm/helm_lint.md) command.

* A values file can be provided via parameter `valueFile`
* Strict linting can be configured via parameter `strictLint`

### Template

goal locally renders templates using the [helm template](https://github.com/helm/helm/blob/master/docs/helm/helm_template.md) command.

* A values file can be provided via parameter `valueFile`
* An output file can be provided via parameter `outputFile`

## Example

To use the `deployAtEnd` functionality it's mandatory to put the Helm Maven Plugin configuration in the parent pom.

```xml
<build>
  <plugins>
    ...
    <plugin>
      <groupId>com.deviceinsight.helm</groupId>
      <artifactId>helm-maven-plugin</artifactId>
      <version>2.11.1</version>
      <configuration>
        <chartName>my-chart</chartName>
        <strictLint>true</strictLint>
        <valuesFile>src/test/helm/my-chart/values.yaml</valuesFile>
        <deployAtEnd>true</deployAtEnd>
        <repos>
          <repo>
            <url>https://your-chart-museum-host.example/</url>
          </repo>
        </repos>
      </configuration>
      <executions>
        <execution>
          <goals>
            <goal>package</goal>
            <goal>lint</goal>
            <goal>template</goal>
            <goal>deploy</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

## Releasing

Creating a new release involves the following steps:

1. `./mvnw gitflow:release-start gitflow:release-finish`
2. `git push origin master`
3. `git push --tags`
4. `git push origin develop`

In order to deploy the release to Maven Central, you need to create an account at https://issues.sonatype.org and
configure your account in `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>ossrh</id>
      <username>your-jira-id</username>
      <password>your-jira-pwd</password>
    </server>
  </servers>
</settings>
```

The account also needs access to the project on Maven Central. This can be requested by another project member.

Then check out the release you want to deploy (`git checkout x.y.z`) and run `./mvnw deploy -Prelease`.
