FROM arm32v7/openjdk:11

LABEL maintainer="SiropOps <Cyril.Boillat@gmail.com>"

ENV TZ=Europe/Zurich

ADD ./target/app.jar /app/

EXPOSE 8011

CMD ["java", "-Xmx1024m", "-jar", "/app/app.jar"]