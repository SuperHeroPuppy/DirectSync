# Direct Sync builds

The `main` branch keeps all three Fabric versions in separate folders. The `1.20.1`, `1.21.1`, and `26.3` branches each put their corresponding Gradle project at the repository root. Each release has mod version `1.0.0`, but the JARs target different Minecraft versions and must not be mixed.

On a version branch, run `.\gradlew.bat build` from the repository root. On `main`, build from the matching Fabric subfolder:

| Minecraft | Project folder | Build command from this directory |
| --- | --- | --- |
| 1.20.1 | `Fabric_1.20.1` | `.\Fabric_1.20.1\gradlew.bat -p Fabric_1.20.1 build` |
| 1.21.1 | `Fabric_1.21.1` | `.\Fabric_1.21.1\gradlew.bat -p Fabric_1.21.1 build` |
| 26.3 | `Fabric_26.3` | `.\Fabric_26.3\gradlew.bat -p Fabric_26.3 build` |

The outputs are in `build/libs/direct-sync-1.0.0.jar` on a version branch or the matching folder's `build/libs/` on `main`. Install the matching output on both the server and its clients. See each version's README for server folders, networking, and update behavior.
