FROM raspbian/stretch

LABEL maintainer="SiropOps <Cyril_Boillat@hotmail.com>"

RUN apt-get -y update -qq && \
	apt-get -y install gpsd gpsd-clients python3 python3-pip vim && \
	apt-get clean && \
	dpkg-reconfigure gpsd && gpsd /dev/ttyUSB0 -F /var/run/gpsd.sock && \
	rm -rf /var/lib/apt/lists/* && \
    rm -rf /tmp/* /var/tmp/*

RUN python3 -m pip install --upgrade pip && \
    python3 -m pip install pika --upgrade
	
COPY ./app.py /app/

CMD ["python3", "/app/app.py"]
