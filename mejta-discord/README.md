# MejtaDiscord

MejtaDiscord (mod ID `mejtadiscord`) connects a server running **MejtaCore** to Discord:
1. **Anti-cheat alerts to Discord.** MejtaAC alerts and kicks, and any other MejtaCore notification you route, are posted as embeds through Discord webhooks.
2. **Minecraft chat to Discord**, through a webhook, under each player's name.
3. **Discord chat to Minecraft** (experimental), read by a bot from one channel and shown in game as plain text.

It never talks to MejtaAC directly. MejtaAC publishes notifications to MejtaCore, and MejtaDiscord forwards them. With nothing configured, it sends and receives nothing.

| | |
|---|---|
| Version | 1.0.0 |
| Minecraft | 26.1.2, Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25 |
| Requires | MejtaCore 1.4.0 (any version from 1.3.0 up to, not including, 2.0.0 is accepted) |
| Other libraries | None. It uses Java's built-in HTTP and WebSocket clients. |

## Installing

1. Put `mejtacore-1.8.0.jar`, `mejtadiscord-1.0.0.jar` and Fabric API in the server's `mods/` folder.
2. Start the server once. Players need nothing.
3. Set it up as below.

**Set secrets (webhook URLs and the bot token) from the server console, never in chat.** The game client saves every typed command in its local `command_history.txt`. See [Security](#security).

## Setting up

### Anti-cheat alerts

1. In Discord, open the alert channel's settings: **Integrations → Webhooks → New Webhook → Copy Webhook URL**.
2. In the server console, run `mejtaadmin config set mejtadiscord webhook.default.url <webhook URL>`.
3. In game, run `/mejtaadmin mejtadiscord test default`. A test embed should appear.

By default `notify.routing` is `mejtaac.*=default`, so all MejtaAC alerts and kicks go to that webhook. To split them, also set `webhook.alerts.url` and `webhook.staff.url`, then set routing, for example `mejtaac.enforcement=staff; mejtaac.alert=alerts`.

**Routing rules:**
- Rules look like `channel=webhook`, separated by `;`. The first match wins.
- `prefix.*` matches the prefix and everything under it. `*` matches everything.
- Webhook names are `default`, `alerts`, `staff`, or `none` (send nowhere).
- A channel no rule matches is not sent.

**Channels you can route:**

| Channel | From | What |
|---|---|---|
| `mejtaac.alert` | MejtaAC | A player failed a check |
| `mejtaac.enforcement` | MejtaAC | A player was kicked (or would have been, in dry-run mode) |
| `mejtautils.freeze`, `mejtautils.invsee`, `mejtautils.staffmode` | MejtaUtils | Staff-tool alerts |
| `mejtaperf.lag`, `mejtaperf.profiler` | MejtaPerf | Lag alerts and saved profiles |
| `<addon>.audit` | MejtaCore | An addon's audit entries, for example `mejtautils.audit` |

MejtaDiscord's own warnings (`mejtadiscord.*`) are never sent to Discord. They go to the log and to in-game holders of `mejtadiscord.admin`.

### Minecraft chat to Discord

1. Create a webhook in your chat channel, as above.
2. In the server console, run `mejtaadmin config set mejtadiscord chat.webhook.url <webhook URL>`.

Each message is posted under the player's name. Turn on `chat.to_discord.join_leave` to post joins and leaves too. Vanished staff (MejtaUtils) are never announced; fake joins and quits are, so they look real. If you set `chat.to_discord.avatar_url` to an image service with `{uuid}` or `{player}`, Discord fetches that URL, so the service learns players' UUIDs or names.

### Discord chat to Minecraft (experimental)

This needs a bot, because webhooks cannot read messages.

1. In the Discord Developer Portal, create an application and open its **Bot** page.
2. Choose **Reset Token** and copy the token. Turn on **Message Content Intent**. Turn off **Public Bot**.
3. Invite the bot with only **View Channels** and **Read Message History** (permissions value `66560`), and limit it to the chat channel. It does not need Send Messages; the chat webhook posts.
4. Turn on Developer Mode in Discord, right-click the chat channel and choose **Copy Channel ID**.
5. In the server console, run:
   ```
   mejtaadmin config set mejtadiscord bot.token <bot token>
   mejtaadmin config set mejtadiscord chat.to_minecraft.channel_id <channel ID>
   ```
6. Run `/mejtaadmin mejtadiscord status`. It should say `Discord to Minecraft: ready`.

**How Discord messages appear in game:**
- As `[Discord] Name: text` (`chat.to_minecraft.format`).
- Bots, webhooks (including the relay's own posts), system messages and other channels are ignored, so nothing echoes.
- Mentions become `@name` and custom emoji `:name:`. Attachments and stickers are noted, never linked.
- Formatting codes, line breaks and invisible characters are removed, and runs of blank characters become one space. A name with nothing left shows as `Discord user`.
- Long messages are cut to `chat.to_minecraft.max_length`. Words in `chat.to_minecraft.word_filter` become asterisks.
- At most `chat.to_minecraft.per_user_per_minute` messages per user, and `chat.to_minecraft.per_minute` from everyone together, are shown.

## Commands

Under `/mejtaadmin mejtadiscord`. Settings are changed with `/mejtaadmin config set mejtadiscord <key> <value>` or MejtaCore's settings menu, and apply at once without losing queued messages.

| Command | Permission | What it does |
|---|---|---|
| `status` | `mejtadiscord.status` | Each webhook's state, with messages sent, dropped and queued, and the last error. Also the bot's state. Never shows URLs or tokens. |
| `test <default\|alerts\|staff\|chat>` | `mejtadiscord.admin` | Sends a test embed to that webhook. Audited. |
| `reconnect` | `mejtadiscord.admin` | Reconnects the bot and re-enables webhooks that Discord refused (401, 403, 404). Audited. |

## Permissions

| Node | Default level | Allows |
|---|---|---|
| `mejtadiscord.status` | 1 (moderators) | `status` |
| `mejtadiscord.admin` | 3 (admins) | `test`, `reconnect`, and MejtaDiscord's in-game warnings |
| `mejtacore.config.secrets` (MejtaCore) | 4 (owners) | Setting webhook URLs and the bot token, together with `mejtacore.config.edit`. The console can always. |

## Settings

Stored in `config/mejta/mejtadiscord.json`. Secret settings are stored separately in `config/mejta/secrets/mejtadiscord.json`.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch |
| `webhook.default.url` | empty (secret) | Webhook for the `default` route |
| `webhook.alerts.url` | empty (secret) | Webhook for the `alerts` route |
| `webhook.staff.url` | empty (secret) | Webhook for the `staff` route |
| `notify.routing` | `mejtaac.*=default` | Channel-to-webhook rules |
| `notify.min_severity` | `INFO` | `INFO`, `WARNING` or `CRITICAL`; lower severities are not sent |
| `notify.username` | `Mejta` | Name on notification messages |
| `notify.avatar_url` | empty | Picture on notification messages (HTTPS); empty uses the webhook's own |
| `chat.to_discord.enabled` | `true` | Relay Minecraft chat (needs `chat.webhook.url`) |
| `chat.webhook.url` | empty (secret) | Webhook in the chat channel |
| `chat.to_discord.username` | `{player}` | Name on relayed chat |
| `chat.to_discord.avatar_url` | empty | Picture on relayed chat; `{uuid}` and `{player}` are filled in |
| `chat.to_discord.join_leave` | `false` | Also post joins and leaves |
| `chat.to_minecraft.enabled` | `true` | **Experimental.** Show the chat channel in game (needs the bot token and channel ID) |
| `bot.token` | empty (secret) | Bot token, without `Bot ` |
| `chat.to_minecraft.channel_id` | empty | The chat channel's ID |
| `chat.to_minecraft.format` | `[Discord] {user}: {message}` | Must contain `{message}` |
| `chat.to_minecraft.max_length` | `256` (16–1000) | Longer messages are cut |
| `chat.to_minecraft.per_user_per_minute` | `10` (1–120) | Messages shown per Discord user per minute |
| `chat.to_minecraft.per_minute` | `30` (1–600) | Messages shown per minute from all users together |
| `chat.to_minecraft.word_filter` | empty | Comma-separated words replaced by asterisks |
| `delivery.max_attempts` | `5` (1–10) | Attempts per message on server or network errors (rate limits are always waited out) |
| `delivery.queue_size` | `200` (10–5000) | Messages waiting per webhook |

Only Discord webhook URLs over HTTPS (`discord.com` or `discordapp.com`, optionally `canary.` or `ptb.`) are accepted, so no setting can send data elsewhere.

## How delivery works

- Each webhook has its own queue and thread. The game never waits on Discord.
- Discord's rate limits are respected and waited out. Server and network errors are retried with backoff up to `delivery.max_attempts`, then the message is dropped and counted in `status`. Dropped alerts are still in MejtaCore's notification log (`mejta-data/mejtacore/notifications.jsonl`).
- A `401`, `403` or `404` means the webhook was deleted or the URL is wrong. That webhook stops and admins are told once. It starts again when its URL changes, or with `reconnect`.
- When a queue is full, routine (INFO) messages are dropped before alerts and kicks.
- The bot keeps a heartbeat, detects dead or stalled connections (20 seconds), resumes after disconnects, starts a new session after 3 failed resumes or 2 minutes, and reconnects with backoff of up to one minute.
- If Discord refuses the bot (bad token, intent not enabled), it stops and says why instead of retrying forever.
- On shutdown, queued messages get up to 3 seconds to go out.

## Security

- **Secrets.** Webhook URLs and the bot token are MejtaCore secret settings:
  - stored in `config/mejta/secrets/mejtadiscord.json` (owner-only where the file system allows), never in the normal config file;
  - never sent to game clients; menus and `config get` show only `(set)` or `(not set)`;
  - settable only from the console or by holders of `mejtacore.config.secrets`;
  - removed from every log line, status line and error message.
- Keep `config/mejta/secrets/` out of shared backups and version control.
- **Nothing from Discord runs anything.** Messages are only shown as plain text, with no click actions and no commands.
- **No pings.** Every webhook request disables mentions, and `@everyone`, `@here` and raw mentions are neutralised in the text as well. Markdown from players is escaped, so they cannot post masked links.
- **The bot is read-only.** Give it View Channels and Read Message History in one channel. Never give it Administrator.
- **What reaches Discord:** the fields of routed notifications (for MejtaAC: player name and UUID, check, measurements, ping and world), relayed chat and player names, and, with an avatar template, UUIDs or names to that image service. No IP addresses.
- **If a secret leaks:** for a webhook, delete it in Discord, create a new one and set the new URL. For the bot token, use **Reset Token** and set the new one; the old one stops working at once.

## Checking it by hand

The automated tests never contact Discord. Before relying on it, check real delivery on a test Discord server:

1. Set `webhook.default.url` and run `test default`. The embed should arrive, and `status` should show `ok, 1 sent`.
2. Trigger a MejtaAC alert. An orange embed should arrive. A kick should arrive as a red embed.
3. Delete the webhook in Discord and trigger another alert. Admins should get "Discord webhook 'default' stopped", and `status` should show `disabled`. `reconnect` should say it was enabled again.
4. Set `chat.webhook.url` and chat in game. The message should appear under your name, and `@everyone` should not ping.
5. Set up the bot and post in the channel. The message should appear in game. Post 12 messages quickly: only 10 should appear.
6. Turn off the Message Content intent and run `reconnect`. Admins should be told about close code 4014, and `status` should show `failed`.
7. Restart the server with the bot running. It should reconnect cleanly, and no URL or token should appear in `logs/latest.log`.

## Building and testing

```bash
cd mejta-core && ./gradlew publishToMavenLocal   # MejtaCore 1.8.0 first
cd ../mejta-discord && ./gradlew build           # compile and run unit tests
./gradlew runClientGameTest                      # client game test
```

- **Unit tests** cover webhook delivery against a local HTTP server (ordering, mentions, rate limits, retries, deleted webhooks, full queues, no URLs in errors), the bot connection against a local fake Discord gateway (heartbeats, resume, reconnect, fatal close codes, stalled handshakes), and text handling (sanitising, redaction, rate limits, routing, embeds).
- **The client game test** joins a dedicated server with a real client and checks the unconfigured state, secret handling, routing, and Discord messages shown in game with their rate limit. It uses a made-up webhook URL that is never contacted.

## Limitations

- One chat channel. Discord threads and forum channels are not relayed.
- Discord edits and deletions are not mirrored in game. Minecraft `/me`, `/say` and `/msg` are not relayed.
- No role sync, account linking or remote administration from Discord.
- Delivery to real Discord is not covered by automated tests; use the hand-check list above.
