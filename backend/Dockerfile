FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src/main src/main
RUN mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar /app/app.jar
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod
CMD ["sh","-c","exec java -jar /app/app.jar --server.port=${PORT:-8080}"]
