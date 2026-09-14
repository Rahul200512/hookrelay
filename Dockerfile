# syntax=docker/dockerfile:1
# JVM image. Layered so a code change re-pushes kilobytes, not the dependency tree.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q --no-transfer-progress dependency:go-offline
COPY src src
RUN ./mvnw -B -q --no-transfer-progress -DskipTests package \
 && java -Djarmode=tools -jar target/hookrelay.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
# 512 MB and a tenth of a CPU on Render's free tier: serial GC and C1-only keep
# startup and footprint small; the workload is waiting on receivers, not computing.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "hookrelay.jar"]
