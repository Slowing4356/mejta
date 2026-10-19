# MejtaDiscord changelog

## 1.0.0

First release. Requires MejtaCore 1.3.0 or newer, below 2.0.0 (API version 4); built and tested against MejtaCore 1.4.0.

- **Anti-cheat alerts to Discord:**
  - MejtaCore notifications are posted as embeds through up to three webhooks, chosen by routing rules. The default rule sends `mejtaac.*` to the `default` webhook.
  - Severities have colours, and Discord's length limits are respected.
  - Mentions are never parsed.
  - Rate limits and retries are handled, and a deleted webhook stops cleanly.
- **Minecraft chat to Discord:** through a chat webhook, under each player's name. Join and leave messages are optional, and vanished staff are never announced.
- **Discord chat to Minecraft (experimental):**
  - A built-in, read-only Gateway v10 client with no library.
  - Messages are shown as plain, single-line text.
  - Limits: per user, for all users together, and on length, plus a word filter.
- **Secrets:** webhook URLs and the bot token are MejtaCore secret settings. They are stripped from every log, status line and error.
- **Commands:** `/mejtaadmin mejtadiscord status`, `test <webhook>` and `reconnect`. `reconnect` also re-enables refused webhooks.

Fixes from the release review, before the first release:
- **Chat from Discord:**
  - Blank and invisible characters can no longer fake a second chat line or a rank badge.
  - A cap for all Discord users together has been added.
- **Bot connection:**
  - Handshake timeouts: a connection that never says hello no longer hangs for ever.
  - Resume attempts give up and start a new session.
  - Closing the bot no longer stalls the server.
- **Webhooks:** queued messages survive changes to the delivery settings, and the webhook worker survives unexpected errors.
