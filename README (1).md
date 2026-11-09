# Dead Man's Hand

*A death-game survival mod for Minecraft 1.19.2 (Fabric or Quilt).*

One night the sky fills with fireworks. They come closer and closer, there's a white flash, and you wake up in the
middle of a huge scramble crossing in a city you've never seen. Every person and animal is gone.

Your phone shows a **visa** that is counting down. When it reaches zero, a laser comes down from the sky. The only way
to get more days is to win **games**. At night a building somewhere in the city lights up, and every game is marked
by a playing card:

- **Suit = kind of game.** ♣ teamwork, ♠ physical, ♦ brains, ♥ betrayal.
- **Number = difficulty.** Clearing a game adds that many days to your visa.

Everything in this mod is original: the title, the characters, the city, the dialogue and the art. The games
themselves follow the rules and mechanics of the survival-game story that inspired it. The mod is not affiliated with
that series or its makers.

---

## What you need

- Minecraft **1.19.2**
- **Quilt Loader** or **Fabric Loader** (0.14.21 or newer)
- **Quilted Fabric API** (on Quilt: `4.0.0-beta.30+0.77.0-1.19.2` from https://modrinth.com/mod/qsl) or **Fabric API**
  for 1.19.2. It's a separate jar in the mods folder, and Dead Man's Hand needs it.
- **Don't run Requiem, Possessed Grin or Pandemonium alongside it.** They're separate mods (Possessed Grin is the
  demon one, and it needs Requiem). Requiem turns dying into becoming a soul or a demon, which breaks every death in the
  Borderland. Delete their jars from the mods folder of the profile you play this in, or keep a separate profile for
  each. If any of them is loaded, Dead Man's Hand tells you in chat when you join, and names it.

Put the `dead-mans-hand` jar in your `mods` folder, next to Quilted Fabric API (or Fabric API). Without it the game
crashes on start with "Registry is already frozen".

## Getting there

- **On its own:** after your first full day in a world, the fireworks come for you around midnight.
- **Straight away:** in a world with cheats on, type `/borderland enter`.

You arrive at the crossing with **1 day** on your visa, which is 20 real minutes. The first venue lights up as soon as
you've had a moment to look around. Your screen shows its distance and direction:

`VENUE OPEN  140 m NE`

Use the **phone** (right-click) for your visa, your cards, or the rules of the game you're in.

## How a game works

1. **Entry.** A venue lights up near you at dusk, but nobody knows which game it is. The sign only says to step
   inside. Walk into the lit entrance and you're registered. Entry closes one minute after the first person signs up,
   or when every place is taken. Empty places are filled by other players who woke up the same night.
2. **No way out.** From the moment you register, leaving the venue means the laser. In survival you're switched to
   adventure mode for the game, so the venue can't be dug through, and you get your game mode back afterwards.
3. **Rules.** When entry closes, the announcer reads them out, as in the show: "Entry is closed. The game will now
   begin. Game: Tag. Difficulty: Five of Spades." and then every rule, one by one, and "Game start." Then the clock
   starts. The boss bar shows the time left.
4. **Clear it** and you get the card and its days ("Game clear."). **Fail** and you die ("Game over.").
5. **Afterwards** you can always get out: if the game leaves you somewhere with no way out on foot (the roof of the
   tower, a sealed room), you're shown out to the street, and every survivor gets a **[Leave the venue]** button.

### The announcer and the screens

**The voice.** A calm woman's voice comes over the speakers: the briefing, every game's rules, the clock ("Five minutes
left."), the rounds, GAME CLEAR and GAME OVER. 517 lines are recorded. Lines with names in them ("Ivy has no cards
left.") are written in chat but not spoken. It plays on the **Voice/Speech** volume slider in Minecraft's Music & Sounds
settings, and sounds the same wherever you stand, like a public address.

**The screens.** Every venue has a screen by its entrance (on a wall, or a black monitor on a post where there's no
wall), and another where you start, if you can't see the first one from there. They show the card, the game, its
difficulty, each rule as it's read out, the time left, and at the end GAME CLEAR or GAME OVER. They glow in the dark,
are fixed to the wall (nobody can take or turn them), and are taken down when the venue closes.

While you're registered for a game or playing one, your visa can't run out. You'll either win days or die in the game.

**Dying over there** wakes you up at home: was any of it real? A day later, the fireworks can come again. With
`permadeath=true` in the config, death is final: you stay behind as a spectator and watch.

## The games

Season one is complete: all ten number-card games from the show, plus the seven of spades it only shows in a flashback.

### 3♣ LIVE OR DIE
A plain grey block of a building, two rooms wide and four deep: eight rooms. You register in the lobby (one phone per
person) and the bars close behind you. Every room has two doors, signed **LIVE** and **DIE**, and the signs mean
nothing. One door leads on through the building; behind the other is a cupboard of a room with no way out, and the
laser. Every room's clock is shorter than the last (2:00, 1:50, 1:40 ... down to ten seconds in the eighth), a room
whose time runs out fills with smoke and burns, and once you've moved on, the room behind you burns too.
The way to live is the building itself: keep its shape in your head. A door in a wall with windows can only lead
outside. Deeper in, some windows are bricked up, so you'll need to remember which way you've come. Get out into the
alley behind it alive.

### 5♠ TAG
A nine-storey L-shaped apartment block with three stairwells. Somewhere in it is the **safe zone**, with a spade on
the wall and two buttons. Two different people must press them at the same moment. "It" walks the galleries in a
horse's head and shoots at anyone it sees with a submachine gun. If it touches you, you're dead. The mask means it
only sees straight ahead, so stay out of its line of sight. The courtyard is mined, and a bomb goes off when the
twenty minutes are up.

The others react to it the way frightened people do. When it sees them, they see it close by, or it shoots at them,
they flinch first. Then, depending on who they are and how scared they already are, some freeze for a moment, the calm
ones slip quietly out of its sight, and the terrified ones bolt. Either way they go for the nearest place it can't see
(into a flat, round a corner), not just further away. Once hidden they keep still a while and warn the others where
it is ("It's on floor 4, north side!"), then decide what to do next. Gunfire they only hear makes them turn toward it
and keep away from that floor for a minute.

### 7♥ WOLF AND LAMBS
A glasshouse full of hedges and trees. One of you is the **wolf** and the rest are lambs. Your goggles tell you which
you are. When the wolf and a lamb look each other in the eye, they swap. When the fifteen minutes are up, every lamb's
collar goes off, and only the wolf walks out.

### 4♣ DISTANCE
You wake up in a dark highway tunnel beside an old, graffiti-covered bus. The rules only say *reach the goal*, and
your phone shows a distance. Halfway along there's a supply table, further on a black panther in the dark, and further
still a motorcycle. At the end there's a wall, and when someone reaches it, the water comes. Pay attention to which
way the number moves. The original was 10 km and two hours; this tunnel is scaled down to 400 m and six minutes.

### 4♦ THREE SWITCHES
A reservoir under the city. There are three switches, A, B and C, and one of them lights the bulb in the room next
door. While its door is closed you can flip them as often as you like. While it's open, each switch can be flipped
only once, and the door won't close while anyone is inside. You get **one** answer, on the panel. All the while the
water is rising, and when it reaches the transformer, everyone in there dies.

### 10♣ BINGO IN THE DARK
A match factory: twenty-five huge rooms in a five-by-five grid, all pitch dark. You start somewhere in the dark with
**nine matches** (use one to strike it: ten seconds of light). Each room has its number painted on its walls, and
lighting a match there puts the number on everyone's bingo card in that room's square. The card is the grid; the
middle is free. A row, a column or a diagonal clears everyone still alive. When your last match goes out, you're out.
Matches can be handed to other people.

### 2♠ HUMAN ELEVATOR
An unfinished tower with an open lift shaft. You hang from the bar under the lift (**hold SNEAK**) while it climbs
sixteen floors to the roof, stopping now and then for as long as it likes. Your grip wears out the longer you hang,
and more slowly if you've eaten well. Look up and **right-click the lift** to shake out an arm and take a fresh hold
(+8% grip, once every 20 seconds). Let go and you fall the height of the building.

### 2♣ BORROWING RACE
A power plant with the alarm going. You play in pairs, and each pair draws a slip naming something to borrow: a red
hard hat, the shift log, a pressure gauge... It's in a barrel somewhere in the plant. Bring it to the control room
desk with your partner beside you before the alarm stops (eight minutes). *The show only shows this game in passing
and never its rules, so these rules are made up to fit what it does show.*

### 6♠ BEAST HUNT
An amusement park after dark, and the big cats are out of their cages: lions, cheetahs, leopards, a cougar and a black
panther. Everyone is prey. Kill every predator and everyone left alive clears it. Weapons are allowed, and there's a
rack of them just inside the gate for whoever gets there first. No time limit.

### 10♥ WITCH HUNT
This one comes to you. It happens at **the Lagoon**, the camp. One night a girl is found at the front desk with a
knife in her heart, and the speakers announce the rules: whoever killed her is the witch, the witch is somewhere in
the camp, and the witch must be thrown into the bonfire in the garden within twenty minutes (two hours in the show).
The guard decides the quickest way is to burn everyone. Think about who could have done it. Read her diary. Use her
body to pick it up and carry it, or use someone standing by the fire to throw them in.

### 7♠ BOILING DEATH
A football stadium. Everyone starts on the pitch, then something goes off underneath it and the bowl starts filling
with boiling water. Climb the stands and get out through a gate in the top of the outer wall before the water reaches
you. Most of the gates are barred, and pieces of the stands are falling away. The rules say only: get out.

## Season two: the face cards

Once you've cleared 5 games (`face_cards_after` in the config), the second stage begins. The games are run by the
people who live here, and each holds a face card worth 11 to 13 days.

### J♥ SOLITARY CONFINEMENT
A prison. Everyone's collar shows a suit on the back. You can see everyone else's on their name tag, but never your
own. You can talk freely: ask someone *"what's my mark?"* and they'll tell you, truthfully or not. When the cells open,
go into a cell alone and give your answer (use the terminal, or `/answer hearts`). If you're wrong, or say nothing,
your collar goes off. Then every mark changes. One of the players is secretly the Jack of Hearts and lies. When the Jack
dies, everyone left clears it, so work out who it is and make sure they're told a lie. Hitting anyone is forbidden.

### K♦ BALANCE SCALE
A courtroom: five chairs facing the board, and you're strapped into yours, under a vat of acid. Every round, everyone picks a number from 0 to 100
(`/answer 37`). The target is the average times 0.8, and whoever is closest wins the round. Everyone else loses a point.
At minus ten your scale tips. Every time someone dies a new rule is added: two players who pick the same number both
lose; an exact hit costs everyone else two points; and with two players left, if one picks 0, a 100 beats it. Leaving
your podium means the laser.

### Q♣ TARGET
A water plant: seven narrow concrete ribs over a deep empty shaft, one rib per pair. Throw the glowing balls to knock
the others off. Fall and you're dead. The last pair standing clears it.

### J♣ BAGWORM
An auditorium hung with ropes of different heights. Hold Shift to keep your grip, swing from rope to rope, and kick
others off (attack them). Touch the floor and you're out. When the Jack, on the highest rope, falls, everyone still
hanging clears it.

### J♠ CHAMPION
A dark dojo. The Jack of Spades and his men come at everyone with blades. Melee weapons are allowed and there's a rack
by the door. Bring the Jack down and everyone still standing clears it.

### Q♠ CHECKMATE
A power station yard: sixteen challengers in blue vests against the Queen's four in red. Every vest has a button on its
back. The teams take turns, sixteen rounds. On your team's turn, get behind someone on the other team and press their
button (use them from behind). Pressed, they're frozen for the rest of the turn and change sides next round. Each team's
king can never change sides. After sixteen rounds the bigger team clears it and the other team dies.

### K♣ OSMOSIS
A yard of shipping containers, two teams of five. Each team splits 10,000 points between its members. Touch someone from
the other team (use or hit them) and it's a battle: whoever has more points wins, and the loser pays 500. Teammates
standing against you add their points to yours. You can only battle the same person once. Six items worth 500 to 3,000
are hidden in the containers (use the glowing block). Touch the other team's base for 10,000. While you touch your own
base your points have no limit, and anyone who battles you there pays 10,000. Below zero you're out. When time runs
out, the team with more points clears it. A fallen player's bracelet can be picked up, and its points add to yours.

### K♥ SILENT
An abandoned building: a pitch-dark concrete maze with mirrors at its corners and corridors that loop. No rules are
given. Something big is in there with you. You'll hear it breathing and roaring, but you'll never see it. It hunts by
sound: sprinting is loud, sneaking is quiet. Find the way out.

### Q♥ CROQUET
A rooftop garden and three sets of croquet against the Queen. Punch your ball to hit it (a full swing hits harder)
through the four hoops in order, then strike the stake. Going through your hoop earns another shot. Finish three sets
and you clear it. Between sets there's tea, and the chance to quit, which means the laser. While you play, she keeps
telling you none of this is real.

### J♦ MAHJONG
A tatami room, four at the table, one of them the Jack, and everyone in a collar that goes off if you leave your seat.
Four hands of simplified mahjong: draw, then discard by clicking a tile in chat. Any complete hand wins (four sets and
a pair, or seven pairs). Click TSUMO or RON when it's offered. After the last hand only first place survives.

### K♠ SURVIVAL
No venue: the game is the whole city. A gunman in a mask, the King of Spades, walks it with an anti-tank rifle and an
assault rifle, his blimp hanging over him so you can always see roughly where he is. You get no weapons, though there
are some in the depot where you register. Outlive him, or bring him down.

### Q♦ PARKING
The show never shows this game. This version is made for the mod. In a multi-storey car park, every level has a row
of eight cars and a board of clues, and exactly one car fits every clue. Start it (use it) and the barrier to the next
ramp lifts. Start the wrong one and it's the last thing you do. Reach the roof before time runs out.

## Season three: the last games

After you've cleared 4 face cards (`final_after` in the config), the last stage begins: all of season three. These games
have no suit and no number: every one of them is a Joker.

### 🃏 OLD MAID
An office floor, seven chairs round a table, every chair wired up. Seven cards each, the Joker among them. Pairs go
on the table, and in turn you take a card from the player on your right (click a number in chat). Here you *want* the
Joker: whoever holds it at the end clears the game. Have it taken from you, run out of cards, or get up from your
chair, and the chair goes off.

### 🃏 SACRED FORTUNES
A shrine. Ten fortune slips are drawn from the box in the grounds, one each, a minute per draw (use the box when it's
your turn). Most ask a question with a number for an answer (`/answer 168`). Get it wrong and fire arrows fall, one for
every unit you were out by, so get under the covered walkways. Some slips are fortunes instead: read them carefully.
Leave the grounds and it's the laser.

### 🃏 ZOMBIE HUNT
A virus research institute: four groups, each dealt one suit, seven number cards each, twenty rounds of one-on-one
matches at the tables. Play by clicking a card in chat, or type `/answer 7` (your number card worth 7), `/answer zombie`,
`/answer shotgun` or `/answer vaccine`; `/answer cards` shows your choices again. Higher card wins and takes the loser's. Each group has one Zombie card,
which beats anything and infects whoever it beats. Everyone has one Shotgun, which kills a zombie and does nothing to a
human. A few have a Vaccine, which stops a Zombie card. Run out of number cards and you're out. After twenty rounds,
whichever side is smaller, humans or zombies, dies.

### 🃏 RUNAWAY TRAIN
Eight carriages. You start in the last and go forward one at a time, with no going back. Each carriage locks behind you
and fills with oxygen or poison. You have thirty seconds to choose mask or no mask (click in chat), and only five
canisters. Four carriages are poison. Every carriage has a canary in a cage, so watch it. In the cab, when the siren
sounds, use the brake.

### 🃏 BINGO TOWER
A red and white lattice tower, and one bingo card shared by everyone (it's on your screen). Climb the scaffolding and
use the numbered lamps on the rings to mark their numbers. FREE, the middle square, is at the very top. A full line,
with FREE pressed, clears it for everyone. Every press drops steel balls from above. You're roped to a partner, and if
you fall to the ground you're out.

### 🃏 KICK THE CAN
A warehouse with ten cans in the middle, and every one is a bomb. Pick one up (use it) and its fuse starts: sixty
seconds. Get it to its own numbered base (use the base) and you're through. You're slow while carrying it. Hand it on by
using another player, and the fuse restarts five seconds shorter. If you get hurt while holding one, it goes off. There
are only ten cans.

### 🃏 FUTURE SUGOROKU
Twenty-five rooms in a five-by-five square, and a way out somewhere. You start with fifteen points on your wristband,
and there are fifteen rounds. Each round the dice are rolled for every door, and the number over it says how many may
go through. You get one door per round. Moving costs a point. Staying costs a point *and* locks your wristband for the
next round, unless two others in your room scan theirs at the panel in the middle. Every room gives or takes points
when you walk in, and rooms already used roll fewer dice. A future is written on every wall. At zero points you're out.
After fifteen rounds the water comes.

### 🃏 ONE LAST GAME
Only for whoever finds the way out of Future Sugoroku. A white, silent room, a table, two cards face down, and the
Watchman. Choose one. Then make the real choice: go back to the living (you wake up at home and the run is over), or
stay in the Borderland for good.

## Dread

- **The laser takes its time.** When it picks someone out, a thin red line comes down out of the sky onto their head,
  they can't move, there's a rising whine, and then it fires.
- **The clock counts down out loud.** You hear "one minute left" and "thirty seconds", then every one of the last
  ten seconds, with a heartbeat.
- **The dead stay where they fell.** Bodies lie there for a few minutes after anyone dies in the Borderland.
- **You're not the only ones.** Now and then, far off across the city, you'll see a red line drop out of the sky onto
  somebody you'll never meet.
- **Fear shows in how they move.** Frightened people run flat out and sometimes freeze.

## The Lagoon (the camp)

A resort hotel in the city where the players who've lasted longest live: a pool, a garden with a bonfire that never
goes out, rooms upstairs, and a guard with guns. The first time you walk in, its leader tells you the rules. Every card
belongs to the camp, so hand yours in at **the wall** in the lobby (hold a card and use the wall). Guns are for the
guard only, and traitors die. When the wall is full, they say, everyone goes home.

The ten of hearts comes to the camp on a night when you're staying there, once you've been there a while and cleared at
least three games of your own.

## The city

The Borderland is its own dimension: an empty Tokyo-like city that generates forever.

- **Downtown towers**, shops and apartments further out, parks, car parks and an **elevated railway**.
- **Abandoned cars** on the roads, vending machines still glowing, and billboards and giant screens for brands
  nobody will buy again.
- The **scramble crossing** in the middle, with screens on every corner. This is where you wake up.
- **The Lagoon**, a resort hotel taken over by the players who've lasted longest (see below).
- **Other players** wander the streets: never many, and never where you're looking. Each one has a name, a past, a
  nature (brave, timid, selfish, kind, clever or reckless) and their own way of playing every game.
- **No animals and no monsters.** Nothing spawns over there.

Every venue stands on its own fixed lot, so you can find them again:

| Card | Game | Entrance (x, z) |
|---|---|---|
| 3♣ | Live or Die | 37, -66 |
| 5♠ | Tag | -166, 120 |
| 7♥ | Wolf and Lambs | 200, 60 |
| 4♣ | Distance | -60, -250 (stairs down to the tunnel) |
| 4♦ | Three Switches | -200, -100 (stairs down to the reservoir) |
| 10♣ | Bingo in the Dark | 100, -69 |
| 2♠ | Human Elevator | -28, 112 |
| 2♣ | Borrowing Race | 164, -69 |
| 6♠ | Beast Hunt | -156, -5 |
| 10♥ | The Lagoon (camp) | 292, -5 |
| J♥ | Solitary Confinement | -92, -69 |

## Talking to the other players (optional AI)

Type in chat near another player and they answer. Without AI they use short built-in lines. With AI they really talk:
they know which game they're in, what's happening around them and how you've treated them.

Open `config/deadmanshand.properties`:

- **Groq (free, online):** sign up at https://console.groq.com, open "API Keys", create a key and paste it after
  `groq_api_key=`. The models in `groq_models` are tried in order. When one hits its free limit, the next takes over.
- **Google AI Studio (free, online):** sign in at https://aistudio.google.com/apikey with a Google account, click
  "Create API key", and paste it after `gemini_api_key=`. The models in `gemini_models` are tried in order. With both
  a Groq and a Google key, `ai_first` says which to use first (`gemini` by default); when one runs out of free
  requests, the other takes over, so two keys last much longer than one.
- **Ollama (free, on your computer):** install it from https://ollama.com, run `ollama pull gemma3:4b`, and set
  `ollama=backup` (used when Groq can't answer) or `ollama=main`.

If you've set up Possessed Grin's AI, Dead Man's Hand copies those settings the first time it starts. Never post your
key anywhere public.

**Talking to them.** Say someone's name and that person answers, even if someone else is closer. Say "everyone" (or
"guys", "listen") and a couple of them answer. Otherwise whoever you're facing answers. While they're working out what
to say you'll see "*Riku is thinking...*". If you talk again before they've answered, they answer that next instead of
talking over you. They know the rules that were read out, who's around them, and what they've already decided, and
they answer what you actually asked. The free AI has limits per minute and per day, so lines nobody asked for are kept
to a few a minute, saving most of the budget for answering you. If every model's free limit runs out, you'll be told,
and they'll fall back to built-in answers (which still know how they feel and what they've decided) until it resets.

**They only know what they've been told.** Walking into a venue, nobody knows what the game is: not the name, not
the card, not the rules. They find out when entry closes and the voice announces it, and they only know the rules that
have been read out so far. Anyone who goes in is registered on the spot, stranger or not, and anyone who then leaves
the venue gets the laser, the same as you. Strangers who won't get a place wait outside the entrance instead of going
in.

**They move like people.** They turn into a new direction instead of snapping round, slow down for a sharp corner,
ease into a walk from standing, and each walks at their own pace. They look where they're going, and at what they came
to look at once they're there. They don't walk into the exact spot someone else is standing on; they stand beside them.
When they're reading a clue or thinking, they stand there for a while instead of pacing about. If they can't quite get
somewhere, they stop close by instead of shuffling at it.

**You know what's happening to them.** If someone says they're coming to you, a line at the bottom of your screen
shows who's on the way, how far off and how many floors up or down, until you can see them. If someone dies out of
sight, you hear it (a scream, and roughly where: above you, below you, close by), and if it was someone you'd been
talking to, you know their voice. Running from something right on top of them always beats a plan: someone on their
way to you will still bolt from the thing in the horse mask.

**What they were thinking is written down.** Every decision, line and action goes into `logs/dead-mans-hand.log` in
your Minecraft folder, with what they were thinking at the time, and anyone who couldn't get somewhere is noted too.
If someone does something stupid, send that file along with what you saw.

**Their bodies give them away.** Heads turn, after a beat, to whoever speaks (you included), and to whoever the red
laser dot lands on. When someone dies close by, they flinch back from it, crouching, and stare. Scared eyes never
settle: they keep glancing about, behind themselves most of all. Grieving eyes stay on the floor or on the body, and
grief slows their walk; someone wrecked with grief kneels by the body. Angry eyes stay on whoever they blame. Frozen
with terror, their eyes dart about while their feet won't move.

**They feel things.** Every other player carries four feelings, fear, grief, anger and hope, and what happens to them
moves those:
- **Seeing someone die** frightens them and makes them grieve, much more if it was someone they'd come to trust.
- **Getting hurt** scares them and makes them angry with whoever did it.
- **Being lied to** makes them angry.
- **Someone getting out** gives them hope.

Kind people grieve harder, timid people scare easier, brave and reckless people shake it off. The feelings fade with
time; grief fades slowest.

And the feelings change what they do:
- **Fear** clouds their judgement (a terrified genius makes mistakes), makes them hang back or run, and can freeze them
  on the spot for a moment.
- **Grief and anger** make them take risks.
- **Hope** steadies them.
- **Despair** (deep grief with no hope left) is when people quit.

You can see it: shaking with fear (sweat), red-faced with anger. Everything the AI has them say is coloured by how they
feel right now.

**The AI decides what they do, not just what they say.** With an AI set up, every other player in a game is asked,
the way you'd ask a person, *what do you do now?*: whenever there's something new to decide, every few seconds while
they walk about, whenever something happens (someone dies, a rule is announced, you speak, someone gets hurt), and
whenever someone speaks to them by name. Each time they're told the rules as they were read out, everything that has
happened and been said lately, who's around them (how far, which way, running or standing), what they can see (the
board, their hand, the clue on the wall, the marks on the others' backs), how they feel, what they decided before and
what they were thinking a moment ago. They think it through, may say something, and do one thing:
- **where they go:** go with someone, go over to someone, get away from someone, head for a place (the weapons rack,
  the haunted house, a gate, a room, the desk), look around somewhere they haven't been, or stay put
- **what they pick:** the door, the number, the card, the tile, the car, the button, the floor to search, the can
- **what they do:** fight or hide, strike a match or save it, mask or no mask, kick, throw, accuse, answer, quit
- **what they say:** to you and to each other. They answer each other by name, ask each other things, lie (if that's
  who they are, or if they're the Jack), bluff, and argue.

They can be wrong. They change their minds, follow someone braver, panic, or freeze. Running away from something right
beside them is instinct, so that doesn't wait for the AI.

The free AI has limits. `ai_decisions_per_minute` (default 50) caps how many decisions go to it; anything past that,
or anything it takes too long over, the built-in thinking decides instead. The free Groq models together allow roughly
a couple of hours of busy games a day before only the small model is left; Ollama on your own computer has no limit.
Set `ai_decides=false` to have the AI only write their words.

**They do what they say, in every game.** Whatever they can decide in a game, they can decide out loud, and then
they do it:
- the door they take, the number they pick, the card they play or take, the tile they throw away
- whether they wear the mask, strike a match, quit, or let go of the bar
- which gate they try, which button they press, which car they start
- who they throw at or kick, whether they fight or hide, whether they carry the witch to the fire

You can ask them too: *"Riku, take the left door"*, *"strike a match"*, *"come with me"*, *"stay here"*. In any game
where people move about freely, they can agree to go with you for a minute, or wait where they are. Whether they
listen depends on how much they trust you, what kind of person they are, and how they're feeling. When they decide
something on their own, they say so first.

## Config (`config/deadmanshand.properties`)

| Setting | Default | What it does |
|---|---|---|
| `crossing` | `auto` | `auto`: the fireworks come at midnight. `command`: only with `/borderland enter`. |
| `crossing_after_days` | `1` | Days you play a world before the first crossing (0 = the very first night). |
| `arrival_visa_days` | `1` | Days on your visa when you arrive. |
| `return_after_days` | `1` | After you die over there and wake up at home: days before the fireworks can come again. |
| `permadeath` | `false` | `true`: dying over there is final, and you stay behind as a spectator. |
| `face_cards_after` | `5` | Games you must clear before the face cards start for you. |
| `final_after` | `4` | Face cards you must clear before the last games start for you. |
| `groq_api_key` | (empty) | Your free Groq key, for the AI. |
| `groq_models` | seven free models | Tried in order; the first is kept for answering you, the rest make the decisions. |
| `gemini_api_key` | (empty) | Your free Google AI Studio key, for the AI. |
| `gemini_models` | `gemini-3.8-flash, gemini-3.5-flash-lite, gemini-2.5-flash, gemini-2.5-flash-lite` | Gemini models, tried in order. |
| `ai_first` | `gemini` | With both keys: `gemini` or `groq` first. |
| `ollama` | `backup` | `off`, `backup` (when Groq can't answer) or `main`. |
| `ai_decides` | `true` | The AI decides everything the others do in a game. `false`: only their words. |
| `ai_decisions_per_minute` | `50` | The most decisions a minute sent to the AI. |

## Commands (cheats on)

| Command | What it does |
|---|---|
| `/borderland enter` | The fireworks come for you now. |
| `/borderland leave` | Go home alive. |
| `/borderland games` | List the games. |
| `/borderland game <name>` | Light up that game's venue (you have to be over there). |
| `/borderland start` | Close entry now, if you're waiting in a venue. |
| `/borderland visa <days>` | Set your visa. |
| `/answer <word>` | For games where you say or choose something (anyone can use it). |
| `/answer leave` | After a game you survived: out of the venue, onto the street. |

## Building it yourself

Push this repository to GitHub with `.github/workflows/build.yml` in place. The **Actions** tab builds the jar and
offers it as a download called `dead-mans-hand`. To build locally, run `gradle build` with Gradle 8.14 and Java 21;
the jar ends up in `build/libs/`.

The announcer's recordings are kept as one file, `voice/announcer.zip`, and unpacked into the mod when it's built. They
were made with Flite (Carnegie Mellon's open-source speech synthesiser, its "slt" voice), then filtered to sound like a
venue loudspeaker.

## Note

This mod was written and checked without running Minecraft: the city and every venue were generated and inspected
block by block outside the game. If something misbehaves in game, `logs/latest.log` says what broke.
