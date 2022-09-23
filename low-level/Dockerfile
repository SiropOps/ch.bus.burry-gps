FROM raspbian/stretch

LABEL maintainer="SiropOps <Cyril_Boillat@hotmail.com>"

RUN apt-get -y update -qq && \
	apt-get -y install gpsd gpsd-clients python python-pip vim && \
	apt-get clean && \
	dpkg-reconfigure gpsd && gpsd /dev/ttyUSB0 -F /var/run/gpsd.sock && \
	rm -rf /var/lib/apt/lists/* && \
    rm -rf /tmp/* /var/tmp/*

RUN python -m pip install --upgrade pip && \
    python -m pip install pika --upgrade
	
COPY ./app.py /app/

CMD ["python", "/app/app.py"]
