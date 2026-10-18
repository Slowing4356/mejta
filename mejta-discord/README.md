# MejtaDiscord

MejtaDiscord (mod ID `mejtadiscord`) is a server-side addon for **MejtaCore**. It does three things:

1. **Anti-cheat alerts to Discord.** MejtaAC's alerts and kicks (and any other MejtaCore notification you route) are posted as embeds through Discord webhooks.
2. **Minecraft chat to Discord**, through a webhook, under each player's name.
3. **Discord chat to Minecraft**, read by a bot from one channel and shown in game as plain text.

It never talks to MejtaAC directly: MejtaAC publishes notifications to MejtaCore, and MejtaDiscord is one of MejtaCore's notification providers.

| | |
|---|---|
| Version | 0.1.0 (first release; **not yet built or run**, see [Status](#status)) |
| Minecraft | 26.1.2, Fabric Loader 0.19.5, Fabric API 0.155.3+26.1.2, Java 25 |
| Requires | **MejtaCore 1.3.0 or newer, below 2.0.0** (API version 4) |
| Third-party libraries | None. Webhooks use Java's built-in `HttpClient`; the gateway uses Java's built-in WebSocket client. |

## Status

This release was written without access to the Minecraft jars or to Discord, so it has **not been compiled against Minecraft, run on a server, or connected to Discord yet**.

- The network and text code contains no Minecraft code and is unit-tested: **38 JUnit tests**.
  - Webhooks are tested against a local HTTP server: ordering, `allowed_mentions`, 429 handling, rate-limit headers, retries, deleted webhooks, a full queue, and no URLs in errors.
  - The gateway is tested against a local WebSocket server that speaks Discord's protocol: identify, heartbeats, zombie connections, resume, reconnect requests, invalid sessions, and fatal close codes.
  - The rest covers the bridge, sanitising, redaction, routing, embeds and inbound message parsing.
- The Minecraft side (settings, commands, chat events) is covered by a client game test, but it has not run yet.
- Delivery to real Discord has to be checked by hand: see [Checking it by hand](#checking-it-by-hand).

## Installing

1. Put `mejtacore-1.3.0.jar`, `mejtadiscord-0.1.0.jar` and Fabric API in the **server's** `mods/` folder.
2. Players need nothing.

With nothing configured, MejtaDiscord sends and receives nothing.

## Setting up

Set secrets **from the server console**, not from in-game chat. The game client saves typed commands in its local `command_history.txt`. See [Security](#security).

### 1. Anti-cheat alerts

1. In Discord, open the channel for alerts, then **Edit Channel → Integrations → Webhooks → New Webhook → Copy Webhook URL**.
2. In the server console, run:
   ```
   mejtaadmin config set mejtadiscord webhook.default.url <paste the URL>
   ```
3. In game, run `/mejtaadmin mejtadiscord test default` (needs `mejtadiscord.admin`). A test embed should appear in the channel.

By default `notify.routing` is `mejtaac.*=default`, so all MejtaAC alerts and kicks go to that webhook.

To split them across channels, set `webhook.alerts.url` and `webhook.staff.url` too, then route channels to them, for example:

```
mejtaadmin config set mejtadiscord notify.routing mejtaac.enforcement=staff; mejtaac.alert=alerts
```

**Routing rules:**
- Rules have the form `channel=webhook` and are separated by `;`.
- The first matching rule wins.
- `prefix.*` matches the prefix and everything under it; `*` matches everything.
- The webhook names are `default`, `alerts`, `staff` and `none`. `none` sends that channel nowhere.
- A channel that no rule matches is not sent.

**Notification channels you can route:**

| Channel | From | What |
|---|---|---|
| `mejtaac.alert` | MejtaAC | A player failed a check: check, reason, measurements, VL, ping, world |
| `mejtaac.enforcement` | MejtaAC | A player was kicked, or would have been in dry-run mode, with everything needed to decide on a ban |
| `mejtautils.freeze`, `mejtautils.invsee`, … | MejtaUtils | Staff-tool events, if you want them in Discord |
| `<addon>.audit` | MejtaCore | Audit entries of an addon, for example `mejtautils.audit` |

MejtaDiscord's own warnings (`mejtadiscord.*`) are never sent to Discord. They go to in-game holders of `mejtadiscord.admin` and to the log.

### 2. Minecraft chat to Discord

1. Create a webhook in your chat channel, as above.
2. In the server console, run:
   ```
   mejtaadmin config set mejtadiscord chat.webhook.url <URL>
   ```

**How chat is posted:**
- Each message is posted under the player's name (`chat.to_discord.username`).
- Messages show the webhook's own picture unless `chat.to_discord.avatar_url` is set, for example to a skin-head service with `{uuid}`. Discord then fetches that URL, so the service learns players' UUIDs.
- To also post joins and leaves, turn on `chat.to_discord.join_leave`.
  - Vanished staff (MejtaUtils) are never announced.
  - Staff fake joins and fake quits are announced, so they look real.

### 3. Discord chat to Minecraft (experimental)

This needs a bot, because webhooks cannot read messages.

1. Go to <https://discord.com/developers/applications>, choose **New Application**, then the **Bot** page.
2. On the Bot page:
   1. Choose **Reset Token** and copy the token.
   2. Under **Privileged Gateway Intents**, turn on **Message Content Intent**. Without it, Discord refuses the connection with close code 4014, and the status command says so.
   3. Turn off **Public Bot**.
3. Invite the bot to your server with only the permissions it needs, **View Channels** and **Read Message History**. Use this URL, with your application ID:
   ```
   https://discord.com/oauth2/authorize?client_id=<APPLICATION_ID>&scope=bot&permissions=66560
   ```
   Then restrict the bot to the chat channel with channel permissions. It does not need Send Messages, because the chat webhook posts.
4. In Discord, turn on **User Settings → Advanced → Developer Mode**. Right-click the chat channel and choose **Copy Channel ID**.
5. In the server console, run:
   ```
   mejtaadmin config set mejtadiscord bot.token <token>
   mejtaadmin config set mejtadiscord chat.to_minecraft.channel_id <channel ID>
   ```
6. Check with `/mejtaadmin mejtadiscord status`. It should say `Discord to Minecraft: ready`.

**How messages appear in game:**
- Messages appear as `[Discord] Name: text` (`chat.to_minecraft.format`).
- These are ignored:
  - bots;
  - webhooks, including the chat relay's own posts, so nothing echoes;
  - system messages;
  - other channels.
- Mentions become `@name`, and custom emoji become `:name:`.
- Attachments and stickers are noted but never linked.
- Formatting codes and line breaks are removed, and long messages are cut (`chat.to_minecraft.max_length`).
- Each Discord user can relay `chat.to_minecraft.per_user_per_minute` messages per minute.
- `chat.to_minecraft.word_filter` replaces listed words with asterisks.

## Commands

Under `/mejtaadmin mejtadiscord`:

| Command | Permission | What |
|---|---|---|
| `status` | `mejtadiscord.status` | For each webhook: state, messages sent, dropped and queued, and the last error. Also the bot connection's state. Never shows URLs or tokens. |
| `test <default\|alerts\|staff\|chat>` | `mejtadiscord.admin` | Sends a test embed to that webhook. Audited. |
| `reconnect` | `mejtadiscord.admin` | Reconnects the bot, for example after fixing the Message Content intent. Audited. |

Settings are changed with MejtaCore's `/mejtaadmin config set mejtadiscord <key> <value>`, or in the Server Settings menu. Changes apply immediately: only the connections whose settings changed are rebuilt, and queued alerts are kept.

## Permissions

| Node | Default | What |
|---|---|---|
| `mejtadiscord.status` | Moderators | `status` |
| `mejtadiscord.admin` | Admins | `test`, `reconnect`, and MejtaDiscord's in-game warnings (a webhook deleted, the bot refused) |
| `mejtacore.config.secrets` | Owners | (MejtaCore) Setting webhook URLs and the bot token. The console always can. |

## Settings

Stored in `config/mejta/mejtadiscord.json`. Settings marked secret are stored in `config/mejta/secrets/mejtadiscord.json`, readable only by the server's user.

| Key | Default | What |
|---|---|---|
| `enabled` | `true` | Master switch |
| `webhook.default.url` | (secret) | Webhook for the `default` route |
| `webhook.alerts.url` | (secret) | Webhook for the `alerts` route |
| `webhook.staff.url` | (secret) | Webhook for the `staff` route |
| `notify.routing` | `mejtaac.*=default` | Channel-to-webhook rules (see above) |
| `notify.min_severity` | `INFO` | Lower severities are not sent |
| `notify.username` | `Mejta` | Name on alert messages |
| `notify.avatar_url` | empty | Picture on alert messages (HTTPS) |
| `chat.to_discord.enabled` | `true` | Relay Minecraft chat (needs `chat.webhook.url`) |
| `chat.webhook.url` | (secret) | Webhook in the chat channel |
| `chat.to_discord.username` | `{player}` | Name on relayed chat |
| `chat.to_discord.avatar_url` | empty | Picture on relayed chat; `{uuid}` and `{player}` placeholders |
| `chat.to_discord.join_leave` | `false` | Post joins and leaves |
| `chat.to_minecraft.enabled` | `true` | **Experimental.** Show the chat channel in Minecraft (needs the next two) |
| `bot.token` | (secret) | Bot token, without `Bot ` |
| `chat.to_minecraft.channel_id` | empty | The chat channel's ID |
| `chat.to_minecraft.format` | `[Discord] {user}: {message}` | Must contain `{message}` |
| `chat.to_minecraft.max_length` | `256` | 16–1000 |
| `chat.to_minecraft.per_user_per_minute` | `10` | 1–120 |
| `chat.to_minecraft.word_filter` | empty | Comma-separated words |
| `delivery.max_attempts` | `5` | Attempts per message on server or network errors (rate limits are always waited out) |
| `delivery.queue_size` | `200` | Messages waiting per webhook |

Only HTTPS Discord webhook URLs (`discord.com`, `discordapp.com`, `canary.` or `ptb.` subdomains) are accepted, so no setting can send data elsewhere.

## How delivery works

- Each webhook has its own queue and thread. Nothing waits on Discord: not the game, and not MejtaCore's notification thread.
- Discord's rate-limit headers pause sending before a limit is hit. A `429` answer is waited out, then the message is retried.
- Server errors (5xx) and network errors are retried with exponential backoff, up to `delivery.max_attempts`, then the message is dropped.
  - Dropped alerts are not lost: MejtaCore's notification file log (`mejta-data/mejtacore/notifications.jsonl`) and MejtaAC's own records keep them.
- `401`, `403` or `404` mean the webhook was deleted or its URL is wrong. That webhook stops, and admins are told in game once.
- When a queue is full, routine messages (INFO) are dropped before alerts and kicks.
- The bot connection:
  - keeps a heartbeat;
  - detects dead connections;
  - resumes after disconnects, without missing messages where Discord allows;
  - reconnects with backoff, up to one minute.
- If Discord refuses the bot (invalid token, intent not enabled), it stops and says why instead of retrying forever.
- On shutdown, queued messages get up to 3 seconds to go out.

## Security

- **Secrets:** webhook URLs and the bot token are MejtaCore secret settings.
  - **Storage:** they are kept in `config/mejta/secrets/mejtadiscord.json` (owner-only permissions where the file system supports them) and never in the normal config file.
  - **Visibility:**
    - they are never sent to game clients;
    - menus and `config get` show only `(set)` or `(not set)`;
    - setting one is not broadcast to other operators.
  - **Who can set them:** only the console and holders of `mejtacore.config.secrets`. Set them from the console, because the game client keeps typed commands in `command_history.txt`.
  - **Logs:** MejtaDiscord's logs and status output pass through a redactor that removes webhook URLs, anything shaped like a bot token, and the configured secret values. Raw exception messages, which can contain request URLs, are never logged.
  - **Keep them out of shared places:** keep `config/mejta/secrets/` out of backups you share and out of version control.
- **Nothing from Discord runs anything.** Discord messages are only displayed, as plain text with no click actions. There is no command execution from Discord of any kind.
- **No pings.** Every webhook request disables mentions (`allowed_mentions: {"parse": []}`), and `@everyone`, `@here` and raw mention syntax are neutralised in the text as a second layer. Players cannot post masked links, because markdown is escaped.
- **The bot is read-only.** It needs only View Channels and Read Message History in one channel. Do not give it Administrator.
- **What reaches Discord:**
  - the fields of routed notifications: for MejtaAC, player name and UUID, check, measurements, ping and world;
  - relayed chat and player names;
  - and, if you set an avatar template, UUIDs to that image service.
  - No IP addresses are sent.
- **If a secret leaks:**
  - **Webhook URL:** delete the webhook in Discord, create a new one, and set the new URL.
  - **Bot token:** use Reset Token in the Developer Portal and set the new one. The old one stops working at once.

## Checking it by hand

Use a test Discord server:

1. Set `webhook.default.url`, then run `/mejtaadmin mejtadiscord test default`. The embed should arrive, and `status` should show `ok, 1 sent`.
2. Trigger a MejtaAC alert (see MejtaAC's README). An orange embed should arrive with the check's fields. A kick should arrive as a red embed.
3. Delete the webhook in Discord and trigger another alert. In game, admins should get "Discord webhook 'default' stopped", and `status` should show `disabled`.
4. Set `chat.webhook.url` and chat in game. The message should appear under your name, and `@everyone` in it should not ping.
5. Set up the bot and post in the channel. It should appear in game. Post 12 messages quickly: only 10 should appear.
6. Turn off the Message Content intent and run `reconnect`. Admins should be told about code 4014, and `status` should show `failed`.
7. Restart the server with the bot running. It should reconnect without errors in the log, and no URL or token should appear anywhere in `logs/latest.log`.

## Building and testing

```
cd ../mejta-core && ./gradlew publishToMavenLocal   # MejtaCore 1.3.0 first
cd ../mejta-discord
./gradlew test                  # the 38 unit tests
./gradlew build
./gradlew runClientGameTest     # client game test: unconfigured, secrets, inbound relay, routing
```

The game test logs each check as `[MejtaDiscordTest] PASS/FAIL`. It never contacts Discord: it uses a made-up webhook URL that is never sent to.

### Things to verify

These Minecraft names were not found in the reference sources. Check them on the first build:

- `PlayerChatMessage.signedContent()`, used for the plain chat text in `ChatRelay`;
- `SharedSuggestionProvider.suggest(Iterable<String>, SuggestionsBuilder)`;
- `CommandSourceStack.getTextName()`.

## Limitations

- One chat channel. Discord threads and forum channels are not relayed.
- Discord edits and deletions are not mirrored in game. Neither are Minecraft `/me`, `/say` or `/msg`.
- Role sync, account linking and remote administration are deliberately not included (see `INTEGRATION-DESIGN.md`).
