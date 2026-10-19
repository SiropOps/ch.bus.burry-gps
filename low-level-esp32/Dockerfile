FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1
WORKDIR /app

# Build native dbus-fast only when no platform wheel is available.
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc libc6-dev \
    && rm -rf /var/lib/apt/lists/*

# BlueZ and D-Bus stay on the host; no second daemon in this image.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && apt-get purge -y --auto-remove gcc libc6-dev


COPY app.py publishing.py runtime.py ./
STOPSIGNAL SIGTERM

CMD ["python", "app.py"]
