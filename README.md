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
      <version>2.11.1</version>
      <configuration>
        <chartName>my-chart</chartName>
        <chartRepoUrl>https://charts.helm.sh/stable</chartRepoUrl>
        <helmVersion>3.11.3</helmVersion>
        <strictLint>true</strictLint>
        <valuesFile>src/test/helm/my-chart/values.yaml</valuesFile>
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
[...]
      containers:
        - name: {{ .Chart.Name }}
          image: "{{ .Values.image.repository }}/rubicon:${project.version}"
[...]
```

## Configuration

| Property | Default | Description |
|---|---|---|
| chartName | The Maven `artifactId` | The name of the chart |
| chartVersion | `${project.model.version}` | The version of the chart |
| chartRepoUrl | `null` | The URL of the Chart repository where dependencies are required from and where charts should be published to |
| incubatorRepoUrl | `https://charts.helm.sh/incubator` | The URL to the incubator Chart repository |
| addIncubatorRepo | `true` | Whether the repository defined in `incubatorRepoUrl` should be added when running the package goal |
| forceAddRepos | `false` | Whether to overwrite the repository configuration when adding a new repository with the same name. This flag is only relevant when using Helm 3 and emulates Helm 2 behavior |
| chartPublishUrl | `${chartRepoUrl}/api/charts` | The URL that will be used for publishing the chart. The default value will work if `chartRepoUrl` refers to a ChartMuseum. |
| chartPublishMethod | "POST" | The HTTP method that will be used for publishing requests |
| chartDeleteUrl | `${chartRepoUrl}/api/charts/${chartName}/${chartVersion}` | The URL that will be used for deleting a previous version of the chart. This is used for updating SNAPSHOT versions. The default value will work if `chartRepoUrl` refers to a ChartMuseum. |
| chartRepoUsername | None | The username for basic authentication against the chart repo |
| chartRepoPassword | None | The password for basic authentication against the chart repo |
| chartRepoServerId | None | The ID of the server definition from the settings.xml server list to obtain the chart repo username/password from. If both chartRepoUsername/chartRepoPassword and chartRepoServerId are specified then the values specified in chartRepoUsername/chartRepoPassword take precedence. |
| chartFolder | `"src/main/helm/<chartName>"` | The location of the chart files (e.g. Chart.yaml). |
| skipSnapshots | `true` | If true, SNAPSHOT versions will be built, but not deployed. |
| helmGroupId | `"com.deviceinsight.helm"` | The helm binary `groupId` |
| helmArtifactId | `"helm"` | The helm binary `artifactId` |
| helmVersion | None | The helm binary `version`. (Make sure to use a recent helm binary version that doesn't use the old Helm Chart repositories from `+https://kubernetes-charts.storage.googleapis.com+`, >= 3.4.0 _or_ >= 2.17.0 if you are still using Helm 2) |
| helmDownloadUrl | `"https://get.helm.sh/"` | The URL where the helm binary is downloaded from. |
| helm.skip | `false` | If true, execution will be skipped entirely |
| stableRepoUrl | `"https://charts.helm.sh/stable"` | For helm 2.x: Can be used to overwrite the default URL for stable repository during `helm init` |
| strictLint | `false` | If true, linting fails on warnings (see: [Lint](#lint)) |
| valuesFile | None | values file that should be used for goals [Lint](#lint), [Template](#template) |
| extraValuesFiles | None | a list of additional values files that can be generated dynamically and will be merged with the values.yaml during [Package](#package). |
| outputFile | target/test-classes/helm.yaml | output file for [template goal](#template) |
| deployAtEnd | `false` | If true, the helm chart is deployed at the end of a multi-module Maven build. This option does not make sense for single-module Maven projects. |

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
        <chartRepoUrl>https://charts.helm.sh/stable</chartRepoUrl>
        <helmVersion>3.5.2</helmVersion>
        <strictLint>true</strictLint>
        <valuesFile>src/test/helm/my-chart/values.yaml</valuesFile>
        <deployAtEnd>true</deployAtEnd>
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

## Troubleshooting

1. _**Problem**_  
   The following error message is a common source of trouble, lately:
   ```
   [ERROR] Output: Error: error initializing: Looks like "https://kubernetes-charts.storage.googleapis.com" is not a valid chart repository or cannot be reached: Failed to fetch https://kubernetes-charts.storage.googleapis.com/index.yaml : 403 Forbidden
   
   ...
   
   [ERROR] Failed to execute goal com.deviceinsight.helm:helm-maven-plugin:2.11.1:package (default) on project my-project: Error creating helm chart: When executing '/home/user/.m2/repository/com/deviceinsight/helm/helm/2.16.2/helm-2.16.2-linux-amd64.binary init --client-only' got result code '1' -> [Help 1]
   ```
   _**Solution**_  
   This is likely due to an old version of helm itself. Make sure to configure `<helmVersion>` to a version >= 3.4.0 or, if you are still using Helm 2, a version >= 2.17.0 ([background information](https://github.com/helm/charts#%EF%B8%8F-deprecation-and-archive-notice)).


2. _**Problem**_  
   The following error message appears if you use an old version of helm-maven-plugin:
   ```
   [ERROR] Output: Error: error initializing: Looks like "https://kubernetes-charts-incubator.storage.googleapis.com" is not a valid chart repository or cannot be reached: Failed to fetch https://kubernetes-charts-incubator.storage.googleapis.com/index.yaml : 403 Forbidden
   ```
   _**Solution**_  
   This can be solved by upgrading helm-maven-plugin itself to version 2.7.0 or later ([#67](https://github.com/deviceinsight/helm-maven-plugin/issues/67)).

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
