# Miniony

Miniony is an advanced Minecraft plugin designed for Paper and Bukkit servers that introduces automated minions for farming and woodcutting tasks. These minions help automate resource gathering with intelligent pathfinding and behavior.

## Features

- Farming Minions: Automatically harvests mature crops, replants seeds, and can apply bone meal from their internal storage to speed up growth.
- Lumberjack Minions: Automatically chops down trees and collects wood.
- Intelligent Pathfinding: Minions use nearest-neighbor algorithms to optimize their work routes.
- Internal Storage: Each minion has its own inventory to store harvested materials.
- Easy Management: Simple commands to give, list, and manage minions.

## Commands

- /minion give <player> <type>: Give a specific minion type to a player.
- /minion list: List all active minions.
- /minion info: View information about the current minion.
- /minion reload: Reload the plugin configuration.

Aliases: /minions, /m

## Permissions

- miniony.give: Allows a player to give themselves or others a minion.
- miniony.wand: Allows access to the region selection tool for minion boundaries.
- miniony.place: Allows a player to place down a minion.
- miniony.admin: Grants full administrative access to the plugin.

## Requirements

- Minecraft Version: 1.21 or higher.
- Java Version: 21 or higher.
- Server Software: Paper, Spigot, or any Bukkit-compatible server.

## Installation

1. Download the latest jar file from the releases page.
2. Place the jar file into your server's 'plugins' directory.
3. Restart your server.
4. Configure the settings in the 'plugins/Miniony' folder if necessary.

## Development

This project uses Maven for dependency management and building.


