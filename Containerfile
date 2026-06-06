FROM eclipse-temurin:21-jre-alpine

WORKDIR /deployments

COPY target/*-runner.jar /deployments/app.jar

EXPOSE 9443
EXPOSE 9000

# Heap dimensionado automaticamente pelo container-awareness da JVM (cgroup limits).
# Sobrescrever via -e JAVA_TOOL_OPTIONS="..." no podman run ou no Deployment.
ENV JAVA_TOOL_OPTIONS="-XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m"

CMD ["java", "-jar", "app.jar"]
