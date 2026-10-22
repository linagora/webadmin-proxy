# Build stage: assembles the runtime layout from the prebuilt Maven artifacts.
# target/dependency comes from an explicit `mvn dependency:copy-dependencies
# -DincludeScope=runtime -DoutputDirectory=target/dependency` run before this build
# (done in the Jenkinsfile; run the same command locally). `mvn install` alone does
# NOT populate it.
FROM eclipse-temurin:27-jre-resolute AS deps
WORKDIR /app
COPY target/dependency/ /app/dependencies/
COPY target/classes/ /app/classes/
COPY src/main/conf/ /app/conf/
COPY target/webadmin-proxy-*.jar /app/webadmin-proxy.jar

# Runtime stage
FROM eclipse-temurin:27-jre-resolute

# Run as a non-root user (uid 1000), matching the security requirement.
# GID/UID 1000 already exist in the base image (group 'ubuntu' / user 'ubuntu');
# rename them instead of adding a duplicate.
RUN groupmod -n webadmin ubuntu && usermod -l webadmin -d /home/webadmin -m -s /usr/sbin/nologin ubuntu \
    && chmod 755 /root && chown webadmin:webadmin /root

WORKDIR /root
COPY --from=deps --chown=webadmin:webadmin /app/ /root/

USER webadmin

EXPOSE 8001

ENTRYPOINT ["java", \
    "-Dworking.directory=/root/", \
    "-cp", "/root/dependencies/*:/root/classes:/root/conf:/root/webadmin-proxy.jar", \
    "com.linagora.webadmin.proxy.WebAdminProxyMain"]
