FROM raspbian/stretch

LABEL maintainer="SiropOps <Cyril_Boillat@hotmail.com>"

RUN printf "deb http://legacy.raspbian.org/raspbian/ stretch main contrib non-free rpi\n" > /etc/apt/sources.list && \
    rm -f /etc/apt/sources.list.d/*.list && \
    apt-get -o Acquire::Check-Valid-Until=false -y update -qq && \
    apt-get -y install --no-install-recommends gpsd gpsd-clients python python-pip vim && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    rm -rf /tmp/* /var/tmp/*

RUN python -m pip install pika==1.1.0 --upgrade
	
COPY ./app.py /app/

CMD ["python", "/app/app.py"]
