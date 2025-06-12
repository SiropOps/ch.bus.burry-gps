FROM python:3.9-slim

# Install system dependencies for I2C and GPIO
RUN apt-get update && apt-get install -y \
    i2c-tools \
    libffi-dev \
    build-essential \
    libgpiod-dev \
    gpiod \
    && rm -rf /var/lib/apt/lists/*

# Install Python dependencies
RUN pip install --no-cache-dir \
    adafruit-circuitpython-ina219 \
    RPi.GPIO \
    adafruit-blinka

# Copy the script
COPY monitor.py /app/monitor.py

WORKDIR /app

CMD ["python", "monitor.py"]
