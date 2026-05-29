FROM eclipse-temurin:21-jre-alpine

WORKDIR /deployments

COPY target/*-runner.jar /deployments/app.jar

EXPOSE 9443
EXPOSE 9000

# -Xms256m               : pré-aloca heap para absorver rampa inicial sem OOM
# -Xmx512m               : cobre pool de 25 conexões TLS + overhead Camel + headroom GC
# MaxMetaspaceSize        : ~3x o uso observado em produção aquecida para proxy leve (~40 MB)
# ReservedCodeCacheSize   : limita JIT; uso esperado ~15 MB pós-aquecimento
CMD ["java", \
     "-Xms256m", "-Xmx512m", \
     "-XX:MaxMetaspaceSize=128m", \
     "-XX:ReservedCodeCacheSize=64m", \
     "-jar", "app.jar"]
