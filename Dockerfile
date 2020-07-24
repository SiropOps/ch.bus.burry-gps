FROM openjdk:8-jre-alpine

LABEL maintainer="SiropOps <Cyril.Boillat@gmail.com>"

ENV TZ=Europe/Zurich

RUN apk add --update \
    curl \
    && rm -rf /var/cache/apk/*

ADD ./target/app.jar /app/

EXPOSE 8011

CMD ["java", "-Xmx512m", "-jar", "/app/app.jar"]