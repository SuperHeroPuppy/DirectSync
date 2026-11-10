"""Prepare an isolated local runServer fixture for the live protocol smoke test."""
import json
import pathlib
import sys
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
run = root / "run"
mods = run / "mods"
mods.mkdir(parents=True, exist_ok=True)
(run / "eula.txt").write_text("eula=true\n", encoding="utf-8")
(run / "server.properties").write_text(
    f"server-port={int(sys.argv[1])}\nonline-mode=false\nmotd=Direct Sync development test\n",
    encoding="utf-8",
)
with zipfile.ZipFile(mods / "example.jar", "w") as jar:
    jar.writestr("fabric.mod.json", json.dumps({
        "schemaVersion": 1,
        "id": "examplemod",
        "version": "1",
        "name": "Example Mod",
        "environment": "*",
    }))
print("Prepared runServer fixture on", sys.argv[1])
