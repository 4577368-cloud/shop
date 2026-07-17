# Multi-stage build for Render / any container host
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN mkdir -p /app/data
COPY --from=build /app/target/tangbuy-plugin-*.jar /app/app.jar
ENV JAVA_OPTS=""
EXPOSE 8088
# Render injects PORT; Spring reads server.port=${PORT:8088}
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
