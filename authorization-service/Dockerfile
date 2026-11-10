FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
RUN --mount=type=bind,source=src/main,target=/source \
    mkdir -p src && cp -R /source src/main \
    && sed -i '/^spring\.datasource\.password=/d' src/main/resources/application.properties \
    && mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/authorization-service-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
