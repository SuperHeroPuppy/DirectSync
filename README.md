# Direct Sync

The main project directory holds a separate folder for each Minecraft version. Each folder is a linked Git worktree checked out on its corresponding branch, so all three versions can be open and edited at the same time. The `main` branch holds the repository documentation and shared Git settings.

| Minecraft | Local folder | GitHub branch | Java |
| --- | --- | --- | --- |
| 1.20.1 | `Fabric_1.20.1` | [1.20.1](https://github.com/SuperHeroPuppy/DirectSync/tree/1.20.1) | 17 |
| 1.21.1 | `Fabric_1.21.1` | [1.21.1](https://github.com/SuperHeroPuppy/DirectSync/tree/1.21.1) | 21 |
| 26.3 | `Fabric_26.3` | [26.3](https://github.com/SuperHeroPuppy/DirectSync/tree/26.3) | 25 |

Each version folder has its own source, README, Gradle wrapper, and build output. Run `.\gradlew.bat build` inside that folder. The output is `build/libs/direct-sync-1.0.0.jar`; install the JAR matching the Minecraft version on both server and client. The mod version remains `1.0.0`.

Git commands run inside a version folder act on that version's branch. For example, `git -C Fabric_1.20.1 status` checks the 1.20.1 worktree. The parent repository ignores these linked folders because their files are tracked on the version branches.

To set up all version folders after a fresh clone of `main`, run:

```powershell
git fetch origin
git worktree add -b 1.20.1 Fabric_1.20.1 origin/1.20.1
git worktree add -b 1.21.1 Fabric_1.21.1 origin/1.21.1
git worktree add -b 26.3 Fabric_26.3 origin/26.3
```
