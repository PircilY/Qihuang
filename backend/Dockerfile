ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-17
ARG JAVA_IMAGE=eclipse-temurin:17-jre

FROM ${MAVEN_IMAGE} AS build

WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
RUN mvn -f backend/pom.xml -q -DskipTests package

FROM ${JAVA_IMAGE}

WORKDIR /app
COPY --from=build /workspace/backend/target/tcm-cvd-rag-prototype-0.1.0-SNAPSHOT.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
