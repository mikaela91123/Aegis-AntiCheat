<p align="center">
  <img src="https://i.ibb.co/ZpkXZxk5/ageis-banner.png" alt="Aegis Logo">
</p>

<p align="center">
  <a href="https://modrinth.com/user/zero.exe1">
    <img src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg" alt="Modrinth">
  </a>
  <a href="https://discord.gg/kDsdEvnyty">
    <img src="https://img.shields.io/badge/Discord-Join%20Server-5865F2?style=flat&logo=discord&logoColor=white" alt="Discord">
  </a>
</p>

# About

**Aegis** is an advanced hybrid anti-cheat detection system designed for modern Minecraft servers. Aegis provides the easiest and most robust solution for keeping your server clean and fair. Powered by async packet analysis, Folia support, and deep physics validation, it effectively mitigates combat exploits, movement hacks, and network packet abuse.

Feel free to hop onto our [Discord](https://discord.gg/kDsdEvnyty) if you have any questions or just want to have a chat with us!

## Advanced Checks Available:

* **Combat Protection**
  * `Reach` -> Detects attacks from distances exceeding legitimate reach limits.
  * `Killaura` -> Detects unnatural entity attack patterns, angles, and speeds.
  * `AutoClicker` -> Analyzes click consistency and speed to catch autoclickers.
* **Movement Verification**
  * `Fly` -> Prevents players from flying or hovering in the air.
  * `Speed` -> Ensures player movement speed complies with standard server physics.
  * `Step` -> Prevents players from stepping up blocks instantly.
  * `VerticalDelta` -> Checks for invalid vertical motion and jumps.
  * `Timer` -> Monitors client game speed to prevent speed hacks.
  * `NoFall` -> Blocks players from negating fall damage using packet exploits.
  * `Jesus` -> Prevents walking or standing on water.
* **Packet & Protocol Checks**
  * `Rotation` -> Detects impossible or sudden camera rotations.
  * `BadPackets` -> Identifies malformed, out-of-order, or spoofed network packets.

## Usage Guide

Aegis commands and permissions:
* `/aegis` (or `/ac`) -> Main command for managing Aegis (requires `aegis.command` permission).
* `/appeal <evidenceID> <reason>` -> Allows players to submit ban appeals directly.

For configuration, setup, and support, join our [Discord](https://discord.gg/kDsdEvnyty).

## Building
Simply build the source with Gradle:
```
./gradlew build
```

## Contributing

**Want to help improve Aegis?** There are several ways you can support and contribute to the project:
* Report issues, request features, or share feedback in our Discord community.
* Help other community members with setup and configuration on the Discord.
* Contribute code or optimizations by creating a pull request.

## License
Aegis is licensed under the BSD-3-Clause License. Please see [LICENSE.md](LICENSE.md) for more info.
