# GPS Nano ESP32 — collecteur BLE vers RabbitMQ

Une boucle possède le scanner temporaire puis le client GATT de
`GPS-NanoESP32`, sur la caractéristique `00002a56-0000-1000-8000-00805f9b34fb`.
Le `BLEDevice` découvert est transmis directement au client, sans seconde
découverte implicite. BlueZ et D-Bus restent sur l'hôte.

Le GPS éteint est un état normal : recherche après 60, 120, 240, 480 puis
900 secondes, sans limite de durée et sans arrêt du processus. Une notification
valide réinitialise le délai et les compteurs d'erreurs. Une connexion sans
notification valide pendant 300 s est déconnectée localement puis réessayée.
Les appels BLE ont des délais externes et une déconnexion dans `finally`, y
compris après connexion ou abonnement partiel. Aucun reset global de BlueZ,
de `hci0` ou du Raspberry n'est effectué.

Après cinq erreurs opérationnelles non effacées par une notification, ou dès
qu'un nettoyage est incertain, le processus attend 60 secondes puis sort avec
code 1. Docker doit le relancer. Un démarrage scanner sans réponse n'est pas
réessayé dans le même processus. SIGTERM/SIGINT nettoient les ressources ;
prévoir `--stop-timeout 120`. Une coroutine ignorant l'annulation après deux
secondes de grâce impose une fin de processus temporisée ; cette dernière
issue perd les tampons mémoire et libère les sockets via l'OS.

Le watchdog ne peut pas diagnostiquer une panne matérielle et un `is_connected`
vrai n'est pas une preuve de notifications reçues. Le partage avec la passerelle
est permis par BlueZ ; il reste à valider sur le contrôleur réel. Il n'y a pas
de verrou interprocessus ou d'exclusion pendant toute la connexion GPS.

## Publications compatibles

La queue durable `gps`, le vhost `/`, l'exchange vide, la routing key `gps` et le
type `application/json` sont conservés. Les champs Java restent `gpsType=ESP32`,
`latitude`, `longitude`, `altitude`, `speed` (m/s), `track`, `ept`, `eps`, `epd`,
`epx`, `epy`, `epv`, `epc`, `mode`, `climb`, `time`. Les heures GPS valides de
l'année courante restent normalisées en millisecondes UTC, sinon `null`.

Un thread unique possède Pika, traite les heartbeats et reconnecte avec délai
1, 2, 4…60 secondes. Les notifications ne font aucun accès réseau : elles
alimentent une file mémoire de 128 messages maximum, avec abandon du plus ancien
en saturation. Un envoi non confirmé est réessayé ; doublons possibles si
l'acquittement est perdu. La file est perdue au redémarrage, sans historique
durable. Les confirmations et `mandatory=True` vérifient la publication routée ;
elles ne garantissent pas que le consommateur Java a traité la mesure.
Le message fictif de démarrage daté de 2019 a été retiré. Les notifications
invalides sont ignorées et comptées, sans sortie ni journalisation du brut.

## Configuration

Copier `gps.env.example` **uniquement pour un nouveau déploiement**, puis
renseigner localement les quatre paramètres RabbitMQ existants. Ne jamais
remplacer un fichier de secrets existant par l'exemple.

| Paramètre | Défaut | Effet |
| --- | --- | --- |
| `spring.rabbitmq.host`, `.port`, `.username`, `.password` | obligatoires | Paramètres existants, sans changement de noms |
| `BLUETOOTH_ADAPTER` | `hci0` | Même adaptateur que la passerelle si nécessaire |
| `BLE_SCAN_SECONDS` | `10` | Fenêtre de découverte après démarrage effectif |
| `SCANNER_OPERATION_TIMEOUT_SECONDS` | `20` | Délai de démarrage scanner et d'abonnement |
| `BLE_CLIENT_CONNECT_TIMEOUT_SECONDS` | `30` | Délai externe de connexion GATT |
| `BLE_CLEANUP_TIMEOUT_SECONDS` | `10` | Arrêt scanner / déconnexion |
| `BLE_RECONNECT_DELAY_SECONDS` | `60` | Premier délai après absence/déconnexion |
| `BLE_RECONNECT_MAX_SECONDS` | `900` | Plafond de backoff, supérieur ou égal au premier délai |
| `BLE_NOTIFICATION_IDLE_SECONDS` | `300` | Silence de notification avant reconnexion locale |
| `BLE_FAILURE_LIMIT` | `5` | Erreurs opérationnelles avant sortie contrôlée |
| `BLE_PROCESS_RESTART_DELAY_SECONDS` | `60` | Temporisation avant sortie BLE fatale |
| `HEALTH_INTERVAL_SECONDS` | `30` | Période du bilan JSON en logs |
| `STARTUP_DELAY_SECONDS` | `15` | Attente initiale annulable, `0` accepté |
| `GPS_LOG_FILE` | `/app/fail/gps-ESP32.log` | Fichier de logs 2 Mio × 4 ; vide = stdout seul |

L'ancien `BLE_CONNECT_TIMEOUT_SECONDS=900` interne, qui arrêtait le GPS après
quinze minutes d'absence, est supprimé. Il ne correspond pas au nouveau plafond
de reconnexion. Les valeurs sont validées sans afficher les secrets.
Les délais RabbitMQ sont heartbeat 30 s, socket 5 s, pile 10 s et connexion
bloquée 5 s. Chaque attente AMQP (ouverture du canal, déclaration, activation
des confirmations, publication/confirmation et fermeture) est aussi bornée à
10 s, même si le broker continue à envoyer ses heartbeats sans acquitter.
Le worker interrompt ces attentes à l'arrêt avec un contrôle toutes les 0,2 s.
Cette garde utilise deux points internes de Pika 1.3.2 : conserver les tests
avec le serveur AMQP simulé lors d'une mise à jour de Pika.
La fermeture attend le worker daemon au plus 15 s ; s'il reste bloqué,
le processus ferme finalement ses sockets à la sortie. Aucune opération Pika
ni journalisation par message abandonné n'a lieu dans le callback BLE.
Les pertes sont comptées dans `rabbitmq.dropped` des logs `GPS health` périodiques.

## Logs et santé

Tous les logs sont UTC avec suffixe `Z`. `GPS health` donne états connexion et
scanner, dernières découverte et notification valide, âges monotones, échecs,
scans sans cible, notifications invalides/silencieuses, reprises et niveau.
`last_gatt_read=null` est normal (notifications, aucune lecture directe).
`rabbitmq` expose connexion, taille de file, messages abandonnés et dernier
envoi confirmé. Les secrets et contenus bruts ne sont jamais interpolés dans
les logs applicatifs. Les logs Pika sont limités pour éviter d'exposer leurs
paramètres de connexion. Une absence de découverte entre deux scans est normale.

## Tests et image

```sh
python -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements-test.txt
python -m pytest -q
docker build --platform linux/arm64 -t ch.bus.burry-gps/low-level-esp32:ble-reliability .
```

Sous PowerShell : activer avec `.venv\Scripts\Activate.ps1`. Aucun adaptateur
ni broker réel n'est requis par les tests. L'import d'`app.py` n'effectue aucun
accès réseau, ouverture de fichier ou attente.
L'image utilise Python 3.12, Bleak 3.0.2 et Pika 1.3.2. Aucun daemon BlueZ
n'y est installé. La version Bleak des anciennes images de production reste
inconnue, car l'ancien Dockerfile n'en fixait pas la version.

Exemple pour un **nouveau** collecteur, après arrêt de toute ancienne instance :

```sh
docker run -d --name low-level-esp32-gps --restart=unless-stopped \
  --net=host --stop-timeout 120 \
  --log-opt max-size=10m --log-opt max-file=3 \
  -v /var/run/dbus:/var/run/dbus \
  --env-file gps.env ch.bus.burry-gps/low-level-esp32:ble-reliability
```

Conserver les montages/paramètres existants et vérifier la politique D-Bus de
l'hôte sans ajouter de privilège. Le dépôt ne fournit pas la configuration
Docker réelle du collecteur ; son déploiement ne doit pas être déduit du
Deployment Kubernetes Java.

La procédure **commune aux deux services**, le diagnostic, les critères de
validation, le rollback conservant les anciens conteneurs et l'annexe reboot
sont dans [BLE_RELIABILITY.md de la passerelle](../../ch.bus.bluetooth-mqtt/BLE_RELIABILITY.md).
La vérification finale RabbitMQ/coexistence et ses résultats sont dans
[BLE_FINAL_VALIDATION.md](../../ch.bus.bluetooth-mqtt/BLE_FINAL_VALIDATION.md).
Ces procédures sont manuelles ; aucun déploiement ni commande sur `hiva-oa`
n'est exécuté par ce travail. Réaliser un essai 48–72 h puis plusieurs semaines,
avec GPS actif/éteint et tous les capteurs, avant de conclure à la stabilité.

