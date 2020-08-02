FROM centos:8

LABEL maintainer="SiropOps <Cyril.Boillat@gmail.com>"

ENV TZ=Europe/Zurich

RUN yum -y update && yum install -y wget vim  && \
	wget https://github.com/kelseyhightower/confd/releases/download/v0.16.0/confd-0.16.0-linux-arm64 && \
	mkdir -p /opt/confd/bin && \
	mv confd-0.16.0-linux-arm64 /opt/confd/bin/confd && \
	chmod +x /opt/confd/bin/confd && \
	mkdir -p /etc/confd/{conf.d,templates}

ADD ./credentials.toml /etc/confd/conf.d/credentials.toml
ADD ./credentials.conf.tmpl /etc/confd/templates/credentials.conf.tmpl

CMD ["/opt/confd/bin/confd", "-onetime", "-backend", "env"]