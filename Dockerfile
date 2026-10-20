# syntax=docker/dockerfile:1
# 多阶段构建：编译 24 个模块 → 打定制补丁（工作台/仪表盘/主题/免密）→ 运行时镜像
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# 根 pom 依赖 channels / modules / plugins / starter 四个目录，必须全带上
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests -pl starter -am package

# 把 SelfDesk 定制层打进 jar：
# 覆盖 console 页面、写入品牌与仪表盘脚本、后台首页注入脚本并改主题色、注入演示免密
FROM python:3.12-slim AS patch
WORKDIR /repo
COPY --from=build /src/starter/target/bytedesk-starter.jar /tmp/app.jar
COPY deploy-selfdesk/console ./console
COPY deploy-selfdesk/admin-patch ./admin-patch
COPY deploy-selfdesk/scripts/patch_jar.py ./scripts/patch_jar.py

# 演示免密：默认开启，账号密码与 compose 里的 BYTEDESK_ADMIN_PASSWORD 保持一致
# 正式部署时用 --build-arg SELFDESK_DEMO_AUTOLOGIN=0 关闭
ARG SELFDESK_DEMO_AUTOLOGIN=1
ARG SELFDESK_DEMO_USERNAME=admin@email.com
ARG SELFDESK_DEMO_PASSWORD="SelfDesk#2026"
ENV SELFDESK_JAR=/tmp/app.jar \
    SELFDESK_DEMO_AUTOLOGIN=${SELFDESK_DEMO_AUTOLOGIN} \
    SELFDESK_DEMO_USERNAME=${SELFDESK_DEMO_USERNAME} \
    SELFDESK_DEMO_PASSWORD=${SELFDESK_DEMO_PASSWORD}
RUN python scripts/patch_jar.py

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=patch /tmp/app.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=open \
    JAVA_OPTS="-Xms512m -Xmx1280m" \
    TZ=Asia/Shanghai

EXPOSE 9003
VOLUME ["/app/data"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
