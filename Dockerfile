# 多阶段构建：先编译 24 个模块，再打运行时镜像
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# 根 pom 依赖 channels / modules / plugins / starter 四个目录，必须全带上
COPY . .
RUN mvn -B -q -DskipTests -pl starter -am package

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /src/starter/target/bytedesk-starter.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=open \
    JAVA_OPTS="-Xms512m -Xmx1280m" \
    TZ=Asia/Shanghai

EXPOSE 9003
VOLUME ["/app/data"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
