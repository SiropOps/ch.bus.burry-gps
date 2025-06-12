import time
import RPi.GPIO as GPIO
import board
import busio
from adafruit_ina219 import INA219
from datetime import datetime
import logging.handlers
import sys, traceback
from time import strftime, gmtime

i2c = busio.I2C(board.SCL, board.SDA)
ina = INA219(i2c, addr=0x45)

RELAY_PIN = 17  # BCM GPIO 17 (Pin 11)
GPIO.setmode(GPIO.BCM)
GPIO.setup(RELAY_PIN, GPIO.OUT)
GPIO.output(RELAY_PIN, GPIO.HIGH)  # Repos: relais désactivé

# Deafults
LOG_LEVEL = logging.INFO  # Could be e.g. "DEBUG" or "WARNING"
LOG_FILENAME = "/logs/ina219.log"
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
sys.stdout = MyLogger(logger, logging.INFO)
# Replace stderr with logging to file at ERROR level
sys.stderr = MyLogger(logger, logging.ERROR)

def log_voltage():
    voltage = ina.bus_voltage
    timestamp = datetime.now().strftime("%d-%m-%Y %H:%M:%S")
    line = f"{timestamp} - Tension mesurée : {voltage:.2f} V"
    logger.info(line)

    if voltage > 13.4:
        GPIO.output(RELAY_PIN, GPIO.LOW)  # relais ON
        logger.info('Relais is started at ' + strftime("%d-%m-%Y %H:%M:%S", gmtime()))
    elif voltage < 11.6:
        GPIO.output(RELAY_PIN, GPIO.HIGH)  # relais OFF
        logger.info('Relais is down at ' + strftime("%d-%m-%Y %H:%M:%S", gmtime()))


logger.info('Start Script at ' + strftime("%d-%m-%Y %H:%M:%S", gmtime()))
GPIO.output(RELAY_PIN, GPIO.LOW)  # relais ON
time.sleep(10) # waiting cluster start

logger.info('Sleep end at ' + strftime("%d-%m-%Y %H:%M:%S", gmtime()))


if __name__ == "__main__":
    try:
        while True:
            log_voltage()
            time.sleep(300)  # 5 minutes
    except KeyboardInterrupt:
        GPIO.cleanup()

~
