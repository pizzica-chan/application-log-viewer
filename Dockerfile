# ビルド段階: Maven で fat JAR を生成（Java 8 ターゲットは pom.xml で指定）
FROM public.ecr.aws/docker/library/maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY aplv-java/pom.xml .
COPY aplv-java/src ./src
RUN mvn -q -DskipTests package

# 実行段階: JRE のみで起動
FROM public.ecr.aws/docker/library/eclipse-temurin:8-jre-alpine
WORKDIR /app
COPY --from=build /app/target/aplv-java.jar app.jar
RUN mkdir -p /app/logs/samples /app/tmp
EXPOSE 8766
ENV APLV_HOME=/app
ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["--host", "0.0.0.0", "--port", "8766", "--dir", "/app/logs/samples"]
