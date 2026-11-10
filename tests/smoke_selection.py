"""Prepare with --prepare, start runServer, then run again to test both transports."""
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
PORT = 25702
FIXTURES = {
    "Impaled 1.20.1.jar": ("impaled", "*"),
    "tooltipfix-1.1.1-1.20.jar": ("tooltipfix", "client"),
    "satin-1.14.0.jar": ("satin", "client"),
    "Mod (extra) [1]+fabric.jar": ("extraname", "*"),
    "server-only.jar": ("serveronly", "server"),
    "excluded.jar": ("excluded", "*"),
    "client/clientfolder.jar": ("clientfolder", "client"),
    "server/serverfolder.jar": ("serverfolder", "server"),
    "Blacklist/blacklisted.jar": ("blacklisted", "*"),
}
EXPECTED = {
    "Impaled 1.20.1.jar": "impaled",
    "tooltipfix-1.1.1-1.20.jar": "tooltipfix",
    "satin-1.14.0.jar": "satin",
    "Mod (extra) [1]+fabric.jar": "extraname",
    "clientfolder.jar": "clientfolder",
}


def prepare():
    run = ROOT / "run"
    config = run / "config"
    config.mkdir(parents=True, exist_ok=True)
    (run / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (run / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={PORT}\nonline-mode=false\nview-distance=2\n",
        encoding="utf-8",
    )
    (config / "directsync.properties").write_text(
        "bind=127.0.0.1\nport=auto\nselection=auto\n", encoding="utf-8"
    )
    (config / "directsync-excluded-mods.txt").write_text("excluded.jar\n", encoding="utf-8")
    for name, (mod_id, environment) in FIXTURES.items():
        path = run / "mods" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as jar:
            jar.writestr("fabric.mod.json", json.dumps({
                "schemaVersion": 1, "id": mod_id, "version": "1.0.0",
                "name": mod_id, "environment": environment,
            }))
    print("Prepared selection fixtures. Start runServer, then run this script without --prepare.")


def verify():
    with urllib.request.urlopen(f"http://127.0.0.1:{PORT + 1}/manifest", timeout=10) as response:
        lines = response.read().decode().splitlines()
    assert lines[0] == "directsync/2"
    manifest = {fields[0]: fields for line in lines[1:] if line for fields in [line.split("\t")]}
    assert {name: fields[1] for name, fields in manifest.items()} == EXPECTED, manifest
    java = shutil.which("java")
    assert java, "Java is required"
    jar = ROOT / "build/libs/direct-sync-1.0.0.jar"
    assert jar.is_file(), "Build the mod first"
    for command, port in (("sync-minecraft", PORT), ("sync", PORT + 1)):
        with tempfile.TemporaryDirectory(prefix="directsync-selection-") as temporary:
            game = pathlib.Path(temporary)
            arguments = [java, "-cp", str(jar), "net.supersnetwork.direct_sync.DirectSyncCli"]
            result = subprocess.run(arguments + [command, "--server", "127.0.0.1", "--port", str(port),
                                    "--game-dir", str(game)], capture_output=True, text=True)
            assert result.returncode == 0, result.stdout + result.stderr
            result = subprocess.run(arguments + ["apply", "--game-dir", str(game)],
                                    capture_output=True, text=True)
            assert result.returncode == 0, result.stdout + result.stderr
            installed = {path.name for path in (game / "mods").glob("*.jar")}
            assert installed == set(EXPECTED), installed
            assert not (game / "mods/client").exists()
            for name, fields in manifest.items():
                body = (game / "mods" / name).read_bytes()
                assert len(body) == int(fields[2])
                assert hashlib.sha256(body).hexdigest() == fields[3]
    print("PASS: server includes spaced filenames and root client-only mods; both transports install them; exclusions and server-only folders remain excluded")


if __name__ == "__main__":
    if "--prepare" in sys.argv:
        prepare()
    else:
        verify()
