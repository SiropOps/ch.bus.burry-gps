FROM openjdk:8-jre-alpine

LABEL maintainer="SiropOps <Cyril_Boillat@hotmail.com>"

ENV TZ=Europe/Zurich

RUN apk add --update \
    curl \
    && rm -rf /var/cache/apk/*

ADD ./target/app.jar /app/

EXPOSE 8001

HEALTHCHECK --start-period=20m --interval=30s --timeout=3s --retries=3 CMD curl -v --silent http://localhost:8001/actuator/health 2>&1 | grep UP

CMD ["java", "-Xmx512m", "-jar", "/app/app.jar"]