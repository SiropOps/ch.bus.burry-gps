"""Loopback AMQP peer for real Pika tests; no RabbitMQ service is required."""
import socket
import threading
import time

import pika


class Peer:
    def __init__(self, stall=None, nack=False, returned=False):
        self.stall, self.nack, self.returned = stall, nack, returned
        self.received = []
        self.heartbeats = 0
        self.closed = threading.Event()
        self.stop = threading.Event()
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.listener.settimeout(0.05)
        self.port = self.listener.getsockname()[1]
        self.thread = threading.Thread(target=self.run, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.stop.set()
        self.thread.join(timeout=3)
        self.listener.close()
        assert not self.thread.is_alive(), "AMQP simulator leaked"

    def parameters(self):
        return pika.ConnectionParameters(host="127.0.0.1", port=self.port,
                                         credentials=pika.PlainCredentials("test", "test"),
                                         heartbeat=1, socket_timeout=1, stack_timeout=2,
                                         blocked_connection_timeout=1, connection_attempts=1)

    def run(self):
        connection = None
        try:
            while not self.stop.is_set():
                try:
                    connection, _ = self.listener.accept()
                    break
                except socket.timeout:
                    continue
            if connection is None:
                return
            connection.settimeout(0.01)
            buffer = b""
            next_heartbeat = 0
            publishing = False
            expected = 0
            sequence = 0
            ready = False

            def send(method, channel=0):
                connection.sendall(pika.frame.Method(channel, method).marshal())

            while not self.stop.is_set():
                if ready and time.monotonic() >= next_heartbeat:
                    connection.sendall(pika.frame.Heartbeat().marshal())
                    self.heartbeats += 1
                    next_heartbeat = time.monotonic() + 0.02
                try:
                    data = connection.recv(65536)
                except socket.timeout:
                    continue
                if not data:
                    break
                buffer += data
                while buffer:
                    consumed, frame = pika.frame.decode_frame(buffer)
                    if not consumed:
                        break
                    buffer = buffer[consumed:]
                    if isinstance(frame, pika.frame.ProtocolHeader):
                        send(pika.spec.Connection.Start(server_properties={
                            "capabilities": {"publisher_confirms": True, "basic.nack": True}}))
                    elif isinstance(frame, pika.frame.Method):
                        method = frame.method
                        self.received.append(method.NAME)
                        if method.NAME == self.stall:
                            continue
                        channel = frame.channel_number
                        if isinstance(method, pika.spec.Connection.StartOk):
                            send(pika.spec.Connection.Tune(channel_max=16, frame_max=131072, heartbeat=1))
                        elif isinstance(method, pika.spec.Connection.Open):
                            send(pika.spec.Connection.OpenOk())
                            ready = True
                        elif isinstance(method, pika.spec.Channel.Open):
                            send(pika.spec.Channel.OpenOk(), channel)
                        elif isinstance(method, pika.spec.Queue.Declare):
                            send(pika.spec.Queue.DeclareOk(queue=method.queue, message_count=0, consumer_count=0), channel)
                        elif isinstance(method, pika.spec.Confirm.Select):
                            send(pika.spec.Confirm.SelectOk(), channel)
                        elif isinstance(method, pika.spec.Basic.Publish):
                            publishing = True
                            sequence += 1
                        elif isinstance(method, pika.spec.Channel.Close):
                            send(pika.spec.Channel.CloseOk(), channel)
                        elif isinstance(method, pika.spec.Connection.Close):
                            send(pika.spec.Connection.CloseOk())
                    elif isinstance(frame, pika.frame.Header):
                        expected = frame.body_size
                    elif isinstance(frame, pika.frame.Body) and publishing:
                        expected -= len(frame.fragment)
                        if expected == 0:
                            publishing = False
                            channel = frame.channel_number
                            if self.stall == "ack":
                                continue
                            if self.returned:
                                send(pika.spec.Basic.Return(reply_code=312, reply_text="NO_ROUTE",
                                                            exchange="", routing_key="gps"), channel)
                                connection.sendall(pika.frame.Header(channel, len(frame.fragment),
                                                                     pika.BasicProperties()).marshal())
                                connection.sendall(pika.frame.Body(channel, frame.fragment).marshal())
                            result = pika.spec.Basic.Nack if self.nack else pika.spec.Basic.Ack
                            send(result(delivery_tag=sequence), channel)
        except (ConnectionError, OSError):
            pass  # Expected when the publisher aborts an expired operation.
        finally:
            if connection is not None:
                connection.close()
            self.closed.set()
