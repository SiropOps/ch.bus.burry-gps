"""Exercise actual Pika RPC waits against a simulated, heartbeat-alive peer."""
import time
import threading

import pika
import pytest

from publishing import DeadlineConnection, RabbitPublisher
from amqp_simulator import Peer


def test_confirmation_wait_has_an_application_deadline(monkeypatch):
    monkeypatch.setattr(DeadlineConnection, "operation_timeout", 0.15)
    # A peer can remain alive while never confirming a publication.
    with Peer(stall="ack") as peer:
        publisher = RabbitPublisher(peer.parameters())
        publisher.publish("position")
        publisher.start()
        try:
            deadline = time.monotonic() + 1
            while publisher.health()["consecutive_failures"] == 0 and time.monotonic() < deadline:
                time.sleep(0.005)
            assert publisher.health()["consecutive_failures"] >= 1
            assert publisher.last_success is None
            assert peer.heartbeats > 1
        finally:
            peer.stop.set()
            publisher.close()


@pytest.mark.parametrize("stall", ["Channel.Open", "Queue.Declare", "Confirm.Select", "ack",
                                  "Channel.Close", "Connection.Close"])
def test_all_rpc_waits_expire_and_release_transport(stall, monkeypatch):
    monkeypatch.setattr(DeadlineConnection, "operation_timeout", 0.15)
    with Peer(stall=stall) as peer:
        connection = DeadlineConnection(peer.parameters(), threading.Event())
        started = time.monotonic()
        with pytest.raises(TimeoutError):
            channel = connection.channel()
            channel.queue_declare(queue="gps", durable=True)
            channel.confirm_delivery()
            channel.basic_publish(exchange="", routing_key="gps", body="position", mandatory=True)
            connection.close()
        assert 0.1 <= time.monotonic() - started < 2
        assert connection.is_closed
        assert peer.closed.wait(timeout=1)


@pytest.mark.parametrize("options, error", [({"nack": True}, pika.exceptions.NackError),
                                         ({"returned": True}, pika.exceptions.UnroutableError)])
def test_actual_pika_rejects_negative_confirmations_and_returns(options, error):
    with Peer(**options) as peer:
        connection = DeadlineConnection(peer.parameters(), threading.Event())
        try:
            channel = connection.channel()
            channel.queue_declare(queue="gps", durable=True)
            channel.confirm_delivery()
            with pytest.raises(error):
                channel.basic_publish(exchange="", routing_key="gps", body="position", mandatory=True)
        finally:
            connection.close()
        assert peer.closed.wait(timeout=1)


def test_stop_interrupts_a_confirmation_wait_without_waiting_for_rpc_deadline():
    with Peer(stall="ack") as peer:
        publisher = RabbitPublisher(peer.parameters())
        publisher.publish("position")
        publisher.start()
        try:
            deadline = time.monotonic() + 2
            while "Basic.Publish" not in peer.received and time.monotonic() < deadline:
                time.sleep(0.005)
            assert "Basic.Publish" in peer.received
            started = time.monotonic()
            publisher.close()
            assert time.monotonic() - started < 2
            assert not publisher.thread.is_alive()
            assert peer.closed.wait(timeout=1)
        finally:
            peer.stop.set()
            publisher.close()


def test_success_cancels_deadline_timers():
    with Peer() as peer:
        connection = DeadlineConnection(peer.parameters(), threading.Event())
        channel = connection.channel()
        channel.queue_declare(queue="gps", durable=True)
        channel.confirm_delivery()
        for i in range(3):
            channel.basic_publish(exchange="", routing_key="gps", body=str(i), mandatory=True)
        # Only Pika heartbeat timers remain; our nested RPC checks must be gone.
        timers = connection._impl.ioloop._timer._timeout_heap
        assert not any(timer.callback and "DeadlineConnection._flush_output" in repr(timer.callback)
                       for timer in timers)
        connection.close()
