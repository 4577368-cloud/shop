# Multi-stage build for Render / any container host
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# -Prender strips RocketMQ/Nacos/Redisson/Knife4j from the fat jar (see pom.xml).
RUN mvn -q -DskipTests package -Prender

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN mkdir -p /app/data
COPY --from=build /app/target/tangbuy-plugin-*.jar /app/app.jar
# Render Starter ≈ 512Mi。Heap + Metaspace + CodeCache 再收紧一档，给 direct/native/线程留余量。
# Dashboard 若覆盖 JAVA_OPTS，请保留同等或更严的上限。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=38.0 -XX:InitialRAMPercentage=12.0 -XX:MaxMetaspaceSize=96m -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=32m -Xss256k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8088
# Render injects PORT; Spring reads server.port=${PORT:8088}
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
