# Build

## Prerequisites

- JDK 21 or later
- Maven 3.9+

## Compile and run tests

```bash
mvn verify
```

Tests are integration tests that spin up a reactor-netty proxy instance and MockServer-based OIDC/backend stubs. They run entirely in-process on random ports — no external services required.

## Build a runnable JAR

```bash
mvn package -DskipTests
java -jar target/webadmin-proxy-1.0.0-SNAPSHOT.jar
```

The JAR expects a `configuration.json` file in the working directory (or the path set by the `CONFIGURATION_FILE` system property — check `WebAdminProxyMain` for the exact flag).

## Build a Docker image with JIB

JIB builds a Docker image without requiring a local Docker daemon.

**Push to a registry:**

```bash
mvn compile jib:build -Djib.to.image=<registry>/webadmin-proxy:<tag>
```

**Build to local Docker daemon:**

```bash
mvn compile jib:dockerBuild
```

The default image name is `linagora/webadmin-proxy`. The base image is `eclipse-temurin:26-jre-noble`. Both can be overridden:

```bash
mvn compile jib:build \
  -Djib.base.image=eclipse-temurin:21-jre-jammy \
  -Djib.to.image=myregistry.example.com/webadmin-proxy:1.0
```

The JIB build bundles `src/main/conf/configuration.json` into `/root/conf/` inside the image. Edit that file (or mount a custom one at runtime) before building.

## Checkstyle

The build enforces import ordering via Checkstyle. The rules are in `checkstyle.xml`. Violations fail `mvn compile`. Run the check explicitly with:

```bash
mvn checkstyle:check
```
