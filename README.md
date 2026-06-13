# ReportChat

Minecraft Paper/Bukkit admin report plugin.

## Features

- `/report` works with or without a message.
- Online configured staff receive a report notification with sound.
- Report notification includes a clickable `[개인 채팅 생성]` button.
- Clicking the button creates a private report chat between the reporter and the admin.
- While a player is in a report chat, their normal chat messages are routed only to that report chat.
- Admins can forcibly invite or remove users from the active report chat.
- Admins can close/delete report chats.
- Admin actions are logged to console when enabled.
- Configurable staff list, OP notification, permissions, sounds, cooldown, and timeout.

## Compatibility

Built with Java 21 and Paper API `1.21.8-R0.1-SNAPSHOT`.

The plugin intentionally uses mostly stable Bukkit/Spigot-compatible APIs for better forward compatibility. If your target version is the requested `26.1.2`, verify the final server jar still supports Bukkit plugin API and Java 21+.

## Build

```bash
mvn package
```

Output:

```text
target/ReportChat-1.0.0.jar
```

## Install

1. Copy `target/ReportChat-1.0.0.jar` into your server `plugins/` directory.
2. Start or restart the server.
3. Edit `plugins/ReportChat/config.yml`.
4. Run `/rc reload` or restart.

## Commands

### Player

```text
/report
/report <message>
```

### Admin

```text
/rc open <reportId>
/rc invite <player>
/rc kick <player>
/rc close [chatId]
/rc list
/rc leave
/rc reload
```

`/reportchat` is the full command. `/rc` is an alias.

## Permissions

```text
report.admin   Receive reports and manage report chats. Default: OP
report.reload  Reload plugin config. Default: OP
```

Config can also grant admin access by player name or UUID:

```yaml
admins:
  - "Gamparda"
notify-op: true
```

## Default config

See [`src/main/resources/config.yml`](src/main/resources/config.yml).

## Notes

- `private-chat.one-active-chat-per-player: true` is enabled by default to avoid routing ambiguity.
- Non-admin participants can use `/rc leave`; admins must use `/rc close` or `/rc kick` because this is a management chat.
- If `private-chat.auto-timeout-minutes` is greater than zero, idle report chats close automatically.
