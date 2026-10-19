import json
import threading
import time

from publishing import RabbitPublisher


class Stop:
    def __init__(self, after):
        self.stopped = False
        self.delays = []
        self.after = after

    def is_set(self):
        return self.stopped

    def set(self):
        self.stopped = True

    def wait(self, seconds):
        self.delays.append(seconds)
        if len(self.delays) >= self.after:
            self.stopped = True


def test_rabbit_outage_backoff_is_bounded_and_secrets_redacted(caplog):
    def unavailable(parameters):
        raise OSError("private-password")
    publisher = RabbitPublisher(None, unavailable, max_pending=2)
    publisher.stopping = Stop(after=10)
    for i in range(1000):
        publisher.publish(str(i))
    publisher.run()
    assert publisher.stopping.delays == [1, 2, 4, 8, 16, 32, 60, 60, 60, 60]
    assert list(publisher.pending) == ["998", "999"]
    assert publisher.dropped == 998
    assert publisher.last_success is None
    assert "private-password" not in caplog.text


def test_rabbit_recovery_retries_message_and_confirms_existing_envelope():
    attempts = []
    bodies = []
    connections = []
    class Connection:
        closed = False
        def channel(self):
            return self
        def queue_declare(self, **kwargs):
            assert kwargs == {"queue": "gps", "durable": True}
        def confirm_delivery(self):
            pass
        def basic_publish(self, **kwargs):
            assert kwargs["exchange"] == "" and kwargs["routing_key"] == "gps"
            assert kwargs["properties"].content_type == "application/json"
            assert kwargs["mandatory"] is True
            bodies.append(kwargs["body"])
            publisher.stopping.set()
        def close(self):
            self.closed = True
    def connect(parameters):
        attempts.append(1)
        if len(attempts) <= 2:
            raise OSError("unavailable")
        connection = Connection()
        connections.append(connection)
        return connection
    publisher = RabbitPublisher(None, connect)
    publisher.stopping = Stop(after=100)
    publisher.publish('{"gpsType":"ESP32","speed":10}')
    publisher.run()
    assert len(attempts) == 3
    assert json.loads(bodies[0])["speed"] == 10
    assert not publisher.pending and publisher.failures == 0
    assert publisher.last_success.endswith("+00:00")
    assert connections[0].closed


def test_publish_never_waits_for_a_blocked_broker():
    entered = threading.Event()
    release = threading.Event()
    def blocked(parameters):
        entered.set()
        release.wait(timeout=2)
        raise OSError()
    publisher = RabbitPublisher(None, blocked)
    publisher.start()
    try:
        assert entered.wait(timeout=1)
        for i in range(1000):
            publisher.publish(str(i))
        assert publisher.health()["pending"] == 128
        assert publisher.thread.is_alive()
    finally:
        release.set()
        publisher.close()
    assert not publisher.thread.is_alive()


def test_publication_failure_keeps_pending_message():
    class Connection:
        def channel(self):
            return self
        def queue_declare(self, **kwargs):
            pass
        def confirm_delivery(self):
            pass
        def basic_publish(self, **kwargs):
            raise OSError("no acknowledgement")
        def close(self):
            pass
    publisher = RabbitPublisher(None, lambda _: Connection())
    publisher.stopping = Stop(after=1)
    publisher.publish("payload")
    publisher.run()
    assert list(publisher.pending) == ["payload"]
    assert publisher.last_success is None


def test_rabbit_credentials_and_network_deadlines_from_env(monkeypatch):
    for name, value in {"username": "user", "password": "secret", "host": "localhost", "port": "5672"}.items():
        monkeypatch.setenv("spring.rabbitmq." + name, value)
    publisher = RabbitPublisher.from_env()
    assert publisher.parameters.heartbeat == 30
    assert publisher.parameters.stack_timeout == 10
    assert publisher.parameters.blocked_connection_timeout == 5
