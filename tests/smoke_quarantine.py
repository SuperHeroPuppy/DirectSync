"""Run against the development runServer on port 25702."""
import base64
import hashlib
import io
import json
import pathlib
import socket
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 25702
HOST = "127.0.0.1"
QUARANTINE = ROOT / "run/mods/.directsync-quarantine"


def varint(number):
    output = bytearray()
    while number & ~127:
        output.append((number & 127) | 128)
        number >>= 7
    output.append(number)
    return bytes(output)


def read_varint(stream):
    result = 0
    for shift in range(0, 35, 7):
        part = stream.read(1)
        if not part:
            raise EOFError("Truncated VarInt")
        result |= (part[0] & 127) << shift
        if not part[0] & 128:
            return result
    raise ValueError("VarInt too large")


def send(stream, payload):
    stream.write(varint(len(payload)) + payload)
    stream.flush()


def response(stream):
    length = read_varint(stream)
    frame = io.BytesIO(stream.read(length))
    assert read_varint(frame) == 0
    text_size = read_varint(frame)
    data = json.loads(frame.read(text_size))
    return data["description"]["text"]


def connection(resource):
    sock = socket.create_connection((HOST, PORT), timeout=10)
    sock.settimeout(20)
    wire = sock.makefile("rwb", buffering=0)
    host = ("directsync:4:" + resource).encode()
    handshake = varint(0) + varint(763) + varint(len(host)) + host + PORT.to_bytes(2, "big") + varint(1)
    send(wire, handshake)
    return sock, wire


def fetch(resource):
    sock, wire = connection(resource)
    try:
        send(wire, b"\0")
        result = bytearray()
        while True:
            header, size, offset, body = response(wire).split("|", 3)
            assert header == "directsync/4" and int(offset) == len(result)
            result.extend(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))
            if len(result) == int(size):
                return bytes(result)
            send(wire, varint(1) + len(result).to_bytes(8, "big"))
    finally:
        sock.close()


def make_jar(version):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as jar:
        jar.writestr("fabric.mod.json", json.dumps({
            "schemaVersion": 1, "id": "examplemod", "version": version,
            "name": "Example Mod", "authors": ["Test Developer"]}))
    return output.getvalue()


def offer(body, hash_override=None):
    version = base64.urlsafe_b64encode(b"2").decode().rstrip("=")
    digest = hash_override or hashlib.sha256(body).hexdigest()
    request = f"offer:examplemod:{version}:{len(body)}:{digest}"
    answer = fetch(request).decode()
    assert answer.startswith("directsync-offer/1|"), answer
    return answer.split("|", 1)[1]


assert b"examplemod" in fetch("metadata")
body = make_jar("2")
token = offer(body)
assert token != "rejected"
sock, wire = connection("upload:" + token)
try:
    for offset in range(0, len(body), 16384):
        send(wire, varint(2) + body[offset:offset + 16384])
    assert response(wire) == "directsync-upload/1|quarantined"
finally:
    sock.close()
uploaded = QUARANTINE / f"examplemod-{token}.jar"
assert uploaded.read_bytes() == body
assert (ROOT / "run/mods/example.jar").read_bytes() != body
uploaded.unlink()

token = offer(body, "0" * 64)
assert token != "rejected"
sock, wire = connection("upload:" + token)
try:
    send(wire, varint(2) + body)
    assert response(wire) == "directsync-upload/1|rejected"
finally:
    sock.close()
assert not (QUARANTINE / f"examplemod-{token}.jar").exists()
print("PASS: metadata, newer client upload to quarantine, unchanged active JAR, hash rejection")
