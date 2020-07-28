#!/usr/bin/env python

'''
Created on 14 sept. 2019

@author: SiropOps

'''
import itertools
import json
import logging.handlers
import os
import sys, traceback
import threading
from time import strftime, gmtime
import time
import uuid

from gps import *
import pika

sys.path.insert(0, "/usr/local/bin")

# Deafults
LOG_LEVEL = logging.INFO  # Could be e.g. "DEBUG" or "WARNING"
LOG_FILENAME = "/app/pgps.log"

FAIL_DIR = "/app/fail/"


class MyLogger(object):
    '''
    Make a class we can use to capture stdout and sterr in the log
    '''

    def __init__(self, logger, level):
        """Needs a logger and a logger level."""
        self.logger = logger
        self.level = level

    def write(self, message):
        # Only log if there is a message (not just a new line)
        if message.rstrip() != "":
            self.logger.log(self.level, message.rstrip())


# Configure logging to log to a file, making a new file at midnight and keeping the last 3 day's data
# Give the logger a unique name (good practice)
logger = logging.getLogger(__name__)
# Set the log level to LOG_LEVEL
logger.setLevel(LOG_LEVEL)
# Make a handler that writes to a file, making a new file at midnight and keeping 3 backups
handler = logging.handlers.RotatingFileHandler(LOG_FILENAME, maxBytes=2000000, backupCount=3)
# Format each log message like this
formatter = logging.Formatter('%(asctime)s %(levelname)-8s %(message)s')
# Attach the formatter to the handler
handler.setFormatter(formatter)
# Attach the handler to the logger
logger.addHandler(handler)

console = logging.StreamHandler()
console.setLevel(LOG_LEVEL)
console.setFormatter(formatter)
logger.addHandler(console) 

# Replace stdout with logging to file at INFO level
# sys.stdout = MyLogger(logger, logging.INFO)
# Replace stderr with logging to file at ERROR level
# sys.stderr = MyLogger(logger, logging.ERROR)


class Data(object): 

    def __init__(self, gpsp): 
        
        self.latitude = gpsd.fix.latitude  # Latitude in degrees
        self.epy = gpsd.fix.epy  # Estimated latitude error - meters
        
        self.longitude = gpsd.fix.longitude  # Longitude in degrees
        self.epx = gpsd.fix.epx  # Estimated longitude error - meters
        
        self.altitude = gpsd.fix.altitude  # Altitude - meters
        self.epv = gpsd.fix.epv  # Estimated altitude error - meters
        
        self.speed = gpsd.fix.speed  # Speed over ground - meters per second
        self.eps = gpsd.fix.eps  # Estimated speed error - meters per second
        
        self.time = gpsd.utc  # Time 
        self.ept = gpsd.fix.ept  # Estimated timestamp error - seconds 
        
        self.climb = gpsd.fix.climb  # Climb velocity - meters per second
        self.epc = gpsd.fix.epc  # Estimated climb error - meters per seconds
        
        self.track = gpsd.fix.track  # Direction - degrees from true north
        self.epd = gpsd.fix.epd  # Estimated direction error - degrees
        
        self.mode = gpsd.fix.mode
    
    def __repr__(self):
        return str(self.__dict__)


gpsd = None  # seting the global variable


class GpsPoller(threading.Thread):

    def __init__(self):
        threading.Thread.__init__(self)
        global gpsd  # bring it in scope
        gpsd = gps(mode=1)  # starting the stream of info
        self.running = True  # setting the thread running to true

    def run(self):
        global gpsd
        while self.running:
            gpsd.next()  # this will continue to loop and grab EACH set of gpsd info to clear the buffer


def failOver():
    try:
        logger.info('failOver Started Script')
        gpsp.start()  # start it up
        for _ in itertools.repeat(None, 300):  # repeat 5 minutes
            with open(FAIL_DIR + str(uuid.uuid1()) + '.json', 'w') as outfile:
                json.dump(Data(gpsd).__dict__, outfile)
                time.sleep(1)  # set to whatever
        gpsp.running = False
        logger.info("failOver is done.\nExiting. at " + strftime("%d-%m-%Y %H:%M:%S", gmtime()));
    except Exception as e:
        logger.error('failOver error: ' + str(e))
        gpsp.running = False


def failBack(channel):
    try:
        logger.info('failBack Started Script')
        for file in os.listdir(FAIL_DIR):
            try:
                if file.endswith(".json"):
                    data = json.load(open(FAIL_DIR + file, 'r'))
                    channel.basic_publish(exchange='',
                                          routing_key='gps',
                                          properties=pika.BasicProperties(content_type='application/json'),
                                          body=json.dumps(data)) 
                    os.remove(FAIL_DIR + file)
            except Exception as e:
                logger.error('file ' + file + ' error: ' + str(e))
                os.remove(FAIL_DIR + file)
    except Exception as e:
        logger.error('failBack error: ' + str(e))


logger.info('Start Script at ' + strftime("%d-%m-%Y %H:%M:%S", gmtime()))
os.system('dpkg-reconfigure gpsd')
os.system('gpsd /dev/ttyUSB0 -F /var/run/gpsd.sock')

time.sleep(15)

logger.info('Sleep end at ' + strftime("%d-%m-%Y %H:%M:%S", gmtime()))

if __name__ == '__main__':
    while True:
        try:       
            gpsp = GpsPoller()  # create the thread
            try:
                credentials = pika.PlainCredentials(os.environ['spring.rabbitmq.username'], os.environ['spring.rabbitmq.password'])
                connection = pika.BlockingConnection(pika.ConnectionParameters(os.environ['spring.rabbitmq.host'], os.environ['spring.rabbitmq.port'], '/', credentials))
                if connection.is_open:
                    channel = connection.channel()
                    channel.queue_declare(queue='gps')
                    channel.basic_publish(exchange='',
                            routing_key='gps',
                            properties=pika.BasicProperties(content_type='application/json'),
                            body='{"epd": NaN, "epx": 83.193, "epy": 116.417, "epv": 23.0, "altitude": 358.6, "eps": NaN, "longitude": 6.08235, "epc": NaN, "track": 353.79, "mode": 3, "time": "2019-09-14T22:06:19.000Z", "latitude": 46.237098333, "climb": NaN, "speed": 0.0, "ept": 0.005}') 
        
                    logger.info('RabbitMQ is started at ' + strftime("%d-%m-%Y %H:%M:%S", gmtime()))
                else:
                    logger.error('RabbitMQ is not connected at ' + strftime("%d-%m-%Y %H:%M:%S", gmtime()))
                    failOver()
                    sys.exit(os.EX_SOFTWARE)
            except Exception as e:
                logger.error('RabbitMQ connection is fail at ' + strftime("%d-%m-%Y %H:%M:%S", gmtime()))
                logger.error(str(e))
                failOver()
                sys.exit(os.EX_SOFTWARE)
        
            gpsp.start()  # start it up
            failBack(channel)
            while True:
                channel.basic_publish(exchange='',
                            routing_key='gps',
                            properties=pika.BasicProperties(content_type='application/json'),
                            body=json.dumps(Data(gpsd).__dict__)) 
                time.sleep(1)  # set to whatever
        
            gpsp.running = False
            gpsp.join()  # wait for the thread to finish what it's doing
            connection.close()
            logger.info("Done.\nExiting. at " + strftime("%d-%m-%Y %H:%M:%S", gmtime()));
        
        except Exception as e:
            logger.error('General error: ' + str(e))
            gpsp.running = False
            gpsp.join()  # wait for the thread to finish what it's doing
            sys.exit(os.EX_SOFTWARE)
