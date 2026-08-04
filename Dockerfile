# Multi-stage build for Render / any container host
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN mkdir -p /app/data
COPY --from=build /app/target/tang-source-plugin-*.jar /app/app.jar
# Render Starter ≈ 512Mi。默认 JVM 不限制 Metaspace / CodeCache / 线程栈，
# RSS 容易冲过上限被 OOMKill（exit 137）。SerialGC 降低小容器常驻内存。
# Dashboard 若覆盖 JAVA_OPTS，请保留同等或更严的上限。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=45.0 -XX:InitialRAMPercentage=20.0 -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss256k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8088
# Render injects PORT; Spring reads server.port=${PORT:8088}
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
