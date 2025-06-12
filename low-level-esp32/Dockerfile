FROM python:3.9-slim

# Installer les dépendances système nécessaires à bleak
RUN apt-get update && apt-get install -y --no-install-recommends \
    bluez \
    libglib2.0-dev \
    dbus \
    && rm -rf /var/lib/apt/lists/*

# Installer bleak (et d'autres dépendances si tu veux)
RUN pip install bleak

RUN pip install pika==1.1.0 --upgrade

# Copier ton script python (remplace par le bon nom)
COPY app.py /app/app.py
WORKDIR /app

CMD ["python", "app.py"]
