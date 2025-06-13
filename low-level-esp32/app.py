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
import asyncio
from bleak import BleakClient, BleakScanner
import pika
import datetime
import re

ISO_TIME_PATTERN = re.compile(r"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})Z$")

BLE_DEVICE_NAME = "GPS-NanoESP32"
CHAR_UUID = "00002a56-0000-1000-8000-00805f9b34fb"

sys.path.insert(0, "/usr/local/bin")

# Deafults
LOG_LEVEL = logging.INFO  # Could be e.g. "DEBUG" or "WARNING"
LOG_FILENAME = "/app/fail/gps-ESP32.log"


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

    def flush(self):
        pass
# Configure logging to log to a file, making a new file at midnight and keeping the last 3 day's data
# Give the logger a unique name (good practice)
logger = logging.getLogger(__name__)
# Set the log level to LOG_LEVEL
logger.setLevel(LOG_LEVEL)
# Make a handler that writes to a file, making a new file at midnight and keeping 3 backups
handler = logging.handlers.RotatingFileHandler(LOG_FILENAME, maxBytes=2 * 1024 * 1024, backupCount=3)
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
sys.stdout = MyLogger(logger, logging.INFO)
# Replace stderr with logging to file at ERROR level
sys.stderr = MyLogger(logger, logging.ERROR)


async def find_device():
    logger.info("Recherche du périphérique BLE...")
    devices = await BleakScanner.discover()
    for d in devices:
        if BLE_DEVICE_NAME in d.name:
            logger.info(f"Appareil trouvé : {d.name} ({d.address})")
            return d.address
    raise Exception(f"Appareil BLE '{BLE_DEVICE_NAME}' introuvable.")

def handle_notify(_, data):
    try:
        raw = json.loads(data.decode())
        
        # Vérification du champ time
        if "time" in raw:
            time_str = raw["time"]
            match = ISO_TIME_PATTERN.match(time_str)
            current_year = datetime.datetime.utcnow().year

            if match:
                year, month, day = int(match[1]), int(match[2]), int(match[3])
                if year == current_year and 1 <= month <= 12 and 1 <= day <= 31:
                    try:
                        dt = datetime.datetime.strptime(time_str, "%Y-%m-%dT%H:%M:%SZ")
                        raw["time"] = dt.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
                    except Exception as e:
                        logger.warning(f"Erreur parsing time malgré regex OK : {e}")
                        raw["time"] = None
                else:
                    logger.warning(f"Date hors de l’année courante ou invalide : {time_str}")
                    raw["time"] = None
            else:
                logger.warning(f"Time malformé ignoré : {time_str}")
                raw["time"] = None

        # Mapping vers noms attendus par Java
        json_data = {
            "latitude": raw.get("lat"),
            "longitude": raw.get("lng"),
            "altitude": raw.get("alt"),
            "speed": raw.get("speed"),
            "track": raw.get("track"),
            "ept": raw.get("hdop"),
            "eps": raw.get("hdop"),
            "epd": raw.get("hdop"),
            "epx": raw.get("hdop"),
            "epy": raw.get("hdop"),
            "epv": raw.get("hdop"),
            "epc": None,
            "mode": 3,
            "climb": None,
            "time": raw.get("time")
        }
        
        global channel

        channel.basic_publish(exchange='',
                                routing_key='gps',
                                properties=pika.BasicProperties(content_type='application/json'),
                                body=json.dumps(json_data))
        # logger.info("Données reçues :\n%s", json.dumps(json_data, indent=2))
    except Exception as e:
        logger.warning("Erreur décodage JSON : %s | Brut : %s", e, data)
        sys.exit(os.EX_SOFTWARE)

async def main():
    while True:
        try:
            address = await find_device()
            async with BleakClient(address) as client:
                logger.info("Connexion établie")
                await client.start_notify(CHAR_UUID, handle_notify)
                logger.info("En attente de notifications...\n(CTRL+C pour quitter)")
                while client.is_connected:
                    await asyncio.sleep(1)
            
                logger.warning("Connexion BLE perdue")
        except Exception as e:
            logger.error(f"Erreur dans la boucle BLE : {e}")
        
        logger.info("Tentative de reconnexion dans 60 secondes...")
        await asyncio.sleep(60)

logger.info('Start Script at ' + strftime("%d-%m-%Y %H:%M:%S", gmtime()))

time.sleep(10) # waiting cluster start

logger.info('Sleep end at ' + strftime("%d-%m-%Y %H:%M:%S", gmtime()))

if __name__ == '__main__':
    while True:
        try:
            is_connected = False
            try:
                credentials = pika.PlainCredentials(os.environ['spring.rabbitmq.username'], os.environ['spring.rabbitmq.password'])
                connection = pika.BlockingConnection(pika.ConnectionParameters(os.environ['spring.rabbitmq.host'], os.environ['spring.rabbitmq.port'], '/', credentials))
                if connection.is_open:
                    global channel
                    channel = connection.channel()
                    channel.queue_declare(queue='gps', durable=True)
                    channel.basic_publish(exchange='',
                            routing_key='gps',
                            properties=pika.BasicProperties(content_type='application/json'),
                            body='{"epd": NaN, "epx": 83.193, "epy": 116.417, "epv": 23.0, "altitude": 358.6, "eps": NaN, "longitude": 6.08235, "epc": NaN, "track": 353.79, "mode": 3, "time": "2019-09-14T22:06:19.000Z", "latitude": 46.237098333, "climb": NaN, "speed": 0.0, "ept": 0.005}')
                    is_connected = True
                    logger.info('RabbitMQ is started at ' + strftime("%d-%m-%Y %H:%M:%S", gmtime()))
                else:
                    logger.error('RabbitMQ is not connected at ' + strftime("%d-%m-%Y %H:%M:%S", gmtime()))
            except Exception as e:
                logger.error('RabbitMQ connection is fail at ' + strftime("%d-%m-%Y %H:%M:%S", gmtime()))
            if is_connected:
                try:
                    asyncio.run(main())
                except Exception as e:
                    logger.error('BLE error: ' + str(e))
            if is_connected:
                connection.close()
            time.sleep(60)
        except Exception as e:
            logger.error('General error: ' + str(e))
            sys.exit(os.EX_SOFTWARE)

    sys.exit(os.EX_SOFTWARE)
