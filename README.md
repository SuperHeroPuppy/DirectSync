# Direct Sync builds

Build from the matching Fabric subfolder. Each release has mod version `1.0.0`, but the JARs target different Minecraft versions and must not be mixed.

| Minecraft | Project folder | Build command from this directory |
| --- | --- | --- |
| 1.20.1 | `Fabric_1.20.1` | `.\Fabric_1.20.1\gradlew.bat -p Fabric_1.20.1 build` |
| 1.21.1 | `Fabric_1.21.1` | `.\Fabric_1.21.1\gradlew.bat -p Fabric_1.21.1 build` |
| 26.3 | `Fabric_26.3` | `.\Fabric_26.3\gradlew.bat -p Fabric_26.3 build` |

The outputs are in each folder's `build/libs/direct-sync-1.0.0.jar`. Install the matching output on both the server and its clients. See each folder's README for server folders, networking, and update behavior.
