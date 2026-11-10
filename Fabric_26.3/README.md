# Direct Sync for Fabric 26.3

Direct Sync checks a server's required mods before Minecraft starts multiplayer login or Fabric registry sync. It downloads missing or changed JARs, asks the player to quit, then applies the update after Minecraft exits. The next launch loads the JARs from the client's ordinary `mods/` folder.

Install this 26.3 build and Fabric API on both the server and each client. The mod version remains `1.0.0`; the distributable is `build/libs/direct-sync-1.0.0.jar`. From this folder, build with `.\gradlew.bat build` on Windows or `./gradlew build` elsewhere. Java 25 and the included Gradle 9.8.2 wrapper are required. Use the JAR built for the Minecraft version you are running.

## Server folders

| Location | Purpose |
| --- | --- |
| `mods/*.jar` | Shared mods loaded by the server and sent to clients. Direct Sync itself is included; Fabric API is excluded from automatic transfer. |
| `mods/client/*.jar` | Client-only mods sent to clients. The server does not load them. |
| `mods/server/*.jar` | Server-only mods loaded by the server and excluded from transfer. |
| `mods/Blacklist/*.jar` | Mod IDs forbidden on clients. These JARs are neither loaded nor sent. |
| `mods/.directsync-quarantine/*.jar` | Newer client JARs offered to the server. They are never loaded or distributed automatically. |

Fabric Loader does not scan nested mod folders on its own. To **load `mods/server` on the server**, add this JVM argument to the server's Java startup command, using the server's absolute path:

```text
-Dfabric.addMods=/data/mods/server
```

The argument must appear before `-jar` or the server main class. Replace `/data` with the actual server game directory. Direct Sync logs a warning for each JAR in `mods/server` that Fabric did not load. Keep these JARs out of the root `mods/` folder so they cannot be selected for transfer.

The server creates `config/directsync.properties`, `config/directsync-server-mods.txt`, and `config/directsync-excluded-mods.txt` at first start. By default, `selection=auto` selects compatible Fabric JARs directly in root `mods/`. It excludes JARs declaring `environment: "server"` or `environment: "client"`, and Fabric API. Put other server-only root JAR filenames in `directsync-excluded-mods.txt`, or use `selection=manual` and list exact shared filenames in `directsync-server-mods.txt`. Add Direct Sync to the manual list if you use that mode and want it updated too. `mods/client` is always sent regardless of the root selection mode. Restart the server after changing JARs or selection files.

Shared and client-only filenames and mod IDs must be unique. Each JAR is limited to 128 MiB; the combined manifest is limited to 256 JARs and 1 GiB. The server verifies each file's SHA-256 hash when it starts, and clients verify downloads before applying them.

## Networking

Version 1.0.0 transfers the manifest and JARs through **Minecraft's published TCP port** using a special status connection. For example, a server on port `25602` needs only `25602` published in a Docker bridge configuration. Direct Sync checks before login, so an outdated pack cannot reach Fabric's missing-registry screen first.

The older HTTP sync service is retained for compatibility and manual use. Its default port is Minecraft port plus one (`25603` for Minecraft `25602`), but it does **not** need to be published for 1.0.0 clients and servers. If HTTP cannot bind, Minecraft-port sync still runs. Both sides must use 1.0.0 for the single-port path.

## Client update

Install Direct Sync and Fabric API directly in the client's ordinary `mods/` folder. The client does not need a `mods/client` folder or a `fabric.addMods` JVM argument. Direct Sync shows a progress bar with each mod's icon, name, developer, and version while downloading and verifying it. It puts downloaded shared and client-only JARs directly in ordinary `mods/` after Minecraft closes, including updates to Direct Sync itself. Existing unrelated client mods are left there. Replaced and no-longer-listed JARs that Direct Sync previously installed are moved out of active `mods/` to timestamped folders under `mods/.directsync-backups/`.

If a client has a higher version of a shared mod than the server, Direct Sync offers that JAR through the Minecraft port. The server checks that the offer names an existing shared mod and a higher version, verifies the received JAR's ID, version, size, and SHA-256 hash, and stores it in `mods/.directsync-quarantine/`. The console prints a yellow message asking the owner to review the file and restart the server. **The server never loads or distributes a quarantined JAR automatically.** The owner must inspect and manually install an approved JAR, then restart. The client waits for that review instead of replacing its newer mod with the server's older version. Keep the quarantine folder below its 512 MiB limit by reviewing or removing old submissions.

Choose the server in multiplayer. Wait until the Direct Sync screen says the download is complete. On a Modrinth App instance, **Restart and reconnect** closes Minecraft, applies the update, asks Modrinth to relaunch the same instance, and rejoins the server. **Quit Minecraft** remains available for a manual restart. Restart and reconnect also appears when JARs are already present but the current Minecraft process has not loaded them.

Automatic relaunch uses Modrinth App's official instance launch link. Direct Sync reads the local instance ID from Modrinth's database and passes the server address to Modrinth; it does not reuse Minecraft login credentials. If Modrinth cannot be identified, the screen offers the manual quit option. The update helper records errors in `mods/.directsync-staging/apply.log`. If an update remains pending, close Minecraft and run the manual `apply` command below.

An old `mods/directsync` folder is migrated on the next applied update. Once migrated, remove any old client `fabric.addMods` launcher argument.

## Manual recovery

These commands run from the project directory, with Minecraft closed for `apply`:

```powershell
java -cp .\build\libs\direct-sync-1.0.0.jar net.supersnetwork.direct_sync.DirectSyncCli sync-minecraft --server example.org --port 25602 --game-dir 'C:\Path\To\Minecraft Instance'
java -cp .\build\libs\direct-sync-1.0.0.jar net.supersnetwork.direct_sync.DirectSyncCli apply --game-dir 'C:\Path\To\Minecraft Instance'
```

`sync-minecraft` uses the published Minecraft port. The older `sync` command uses the HTTP port and remains available where that port is exposed.

Run `python tests/smoke_sync.py` after building to check staging, root-folder install, self update, backups, and hash rejection. With the development `runServer` running on port 25702, `python tests/smoke_quarantine.py` checks the live upload protocol and quarantine verification.

Only sync from servers you trust: downloaded JARs can execute code when Minecraft starts. Transfer hashes detect damage, but the server manifest is not authenticated. Blacklist checks use mod IDs reported by the normal client; a modified client can lie about them.
