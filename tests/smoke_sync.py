import hashlib
import http.server
import io
import pathlib
import shutil
import subprocess
import sys
import tempfile
import threading
import urllib.parse
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = shutil.which("java")
if JAVA is None:
    raise RuntimeError("Java is required to run the smoke test")
JAR = ROOT / "build/libs/direct-sync-1.0.0.jar"

def fabric_jar(label, version="1"):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as archive:
        archive.writestr("fabric.mod.json", '{"schemaVersion":1,"id":"' + label + '","version":"' + version + '","name":"Test"}')
    return out.getvalue()

state = {"first.jar": fabric_jar("first")}
corrupt = False

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        request_path = urllib.parse.unquote(self.path)
        if self.path == "/manifest":
            lines = ["directsync/2"]
            for name, body in state.items():
                with zipfile.ZipFile(io.BytesIO(body)) as archive:
                    import json
                    mod_id = json.loads(archive.read("fabric.mod.json"))["id"]
                lines.append(f"{name}\t{mod_id}\t{len(body)}\t{hashlib.sha256(body).hexdigest()}")
            payload = ("\n".join(lines) + "\n").encode()
        elif request_path.startswith("/mods/") and request_path[6:] in state:
            payload = state[request_path[6:]]
            if corrupt:
                payload = payload[:-1] + bytes([payload[-1] ^ 1])
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass

def run(*args, expected=0):
    result = subprocess.run([str(JAVA), "-cp", str(JAR),
        "net.supersnetwork.direct_sync.DirectSyncCli", *args],
        capture_output=True, text=True)
    if result.returncode != expected:
        raise AssertionError(f"exit {result.returncode}, expected {expected}\n{result.stdout}\n{result.stderr}")
    return result

with tempfile.TemporaryDirectory() as tmp:
    game = pathlib.Path(tmp)
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        opts = ("--server", "127.0.0.1", "--port", str(server.server_port), "--game-dir", str(game))
        (game / "mods").mkdir(parents=True)
        personal = fabric_jar("local")
        (game / "mods/local.jar").write_bytes(personal)
        (game / "mods/first.jar").write_bytes(state["first.jar"])
        run("sync", *opts)
        assert not (game / "mods/.directsync-staging/first.jar").exists()
        assert not (game / "mods/client").exists()
        parent = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(1)"])
        helper = subprocess.Popen([str(JAVA), "-cp", str(JAR),
            "net.supersnetwork.direct_sync.DirectSyncCli", "apply-after-exit",
            "--pid", str(parent.pid), "--game-dir", str(game)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        assert not (game / "mods/.directsync-installed-manifest.txt").exists()
        parent.wait(timeout=5)
        output, error = helper.communicate(timeout=10)
        assert helper.returncode == 0, output + error
        assert (game / "mods/first.jar").read_bytes() == state["first.jar"]
        assert not (game / "mods/directsync").exists()
        assert (game / "mods/.directsync-installed-manifest.txt").exists()
        state["first.jar"] = fabric_jar("first", "2")
        run("sync", *opts)
        assert (game / "mods/.directsync-staging/first.jar").exists()
        run("apply", "--game-dir", str(game))
        assert (game / "mods/first.jar").read_bytes() == state["first.jar"]
        assert list((game / "mods/.directsync-backups").glob("*/root/first.jar"))
        state.clear()
        state["second.jar"] = fabric_jar("second")
        run("sync", *opts)
        run("apply", "--game-dir", str(game))
        assert not (game / "mods/first.jar").exists()
        assert (game / "mods/second.jar").read_bytes() == state["second.jar"]
        assert list((game / "mods/.directsync-backups").glob("*/root/first.jar"))
        assert (game / "mods/local.jar").read_bytes() == personal
        assert not (game / "mods/client").exists()
        (game / "mods/directsync").mkdir()
        (game / "mods/directsync/legacy.jar").write_bytes(fabric_jar("legacy"))
        run("sync", *opts)
        run("apply", "--game-dir", str(game))
        assert not (game / "mods/directsync").exists()
        assert list((game / "mods/.directsync-backups").glob("*/legacy/legacy.jar"))
        with tempfile.TemporaryDirectory() as relaunch_tmp:
            relaunch_game = pathlib.Path(relaunch_tmp)
            state.clear()
            state["relaunch.jar"] = fabric_jar("relaunch")
            relaunch_opts = ("--server", "127.0.0.1", "--port", str(server.server_port),
                             "--game-dir", str(relaunch_game))
            run("sync", *relaunch_opts)
            (relaunch_game / "mods/.directsync-staging/relaunch.properties").write_text("url=invalid\n")
            parent = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(1)"])
            helper = subprocess.Popen([str(JAVA), "-cp", str(JAR),
                "net.supersnetwork.direct_sync.DirectSyncCli", "apply-after-exit",
                "--pid", str(parent.pid), "--game-dir", str(relaunch_game)],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            parent.wait(timeout=5)
            output, error = helper.communicate(timeout=10)
            assert helper.returncode == 1 and "Invalid Modrinth relaunch URL" in error
            assert (relaunch_game / "mods/relaunch.jar").read_bytes() == state["relaunch.jar"]
            assert not (relaunch_game / "mods/.directsync-staging/manifest.txt").exists()
        state.clear()
        state["Impaled 1.20.1.jar"] = fabric_jar("impaled")
        state["Mod (extra) [1]+fabric.jar"] = fabric_jar("extraname")
        run("sync", *opts)
        run("apply", "--game-dir", str(game))
        for name, body in state.items():
            assert (game / "mods" / name).read_bytes() == body
        state.clear()
        state["direct-sync-1.0.0.jar"] = fabric_jar("directsync", "1.0.0")
        (game / "mods/direct-sync-old.jar").write_bytes(fabric_jar("directsync", "0.9.0"))
        run("sync", *opts)
        run("apply", "--game-dir", str(game))
        assert not (game / "mods/direct-sync-old.jar").exists()
        assert (game / "mods/direct-sync-1.0.0.jar").read_bytes() == state["direct-sync-1.0.0.jar"]
        assert list((game / "mods/.directsync-backups").glob("*/root/direct-sync-old.jar"))
        state["third.jar"] = fabric_jar("third")
        corrupt = True
        result = run("sync", *opts, expected=1)
        assert "SHA-256 mismatch" in result.stderr
        corrupt = False
        state.clear()
        state["../escape.jar"] = fabric_jar("escape")
        result = run("sync", *opts, expected=1)
        assert "Invalid manifest line" in result.stderr
        assert not (game / "escape.jar").exists()
        print("PASS: root sync, self update, apply after exit, stale backup, legacy migration, relaunch ordering, personal mods, spaced filenames, hash and path rejection")
    finally:
        server.shutdown()
