FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src/main src/main
RUN mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar /app/app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod
CMD ["sh","/app/docker-entrypoint.sh"]
