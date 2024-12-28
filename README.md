# Player Pearls

_A vanilla-friendly minecraft mod to teleport/warp to your friends in survival. Intended for small casual smps._

Sneak and throw an ender pearl up in the air to send out a request.
Your friend can 'accept' your request by sneaking and looking straight down.
You will be teleported right to them!
A simple mechanic supported with sounds and particles to fit right into the game.

![Teleportation example](https://i.imgur.com/SvOBVQG.gif)

### Pearl Requests
**Sneak and throw a pearl straight up in the air to send out a teleport request, and enter a pending state.**

![Pending state example](https://i.imgur.com/THZBVUh.gif)

In this pending state, your xp will drain until the request is **accepted** or **cancelled**.

A request is cancelled if:
- 5 xp levels are lost
- the player runs out of xp
- the player moves too far away from their original location

_A third of the lost xp is returned to the player when a request is cancelled._

Once a player is in this state, they can be teleported if another player accepts their request.

### Accepting Requests
**Sneak and look straight down to accept a pending request.**

![Accepting request example](https://i.imgur.com/dpPzQ71.gif)

When a player sneaks and looks straight down, all players currently in a pending state will be teleported to them.

This will deposit the xp lost by each of the players involved in the teleport:
- Half of the xp levels are dropped in the initial location that the player teleported from
- The other half is dropped at the location that the player teleported to

---
_Player pearls adds no new items, blocks or entities and only has to be installed on the server._