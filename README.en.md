<p align="right">
  <a href="README.md"><img alt="Русский" src="https://img.shields.io/badge/%D1%8F%D0%B7%D1%8B%D0%BA-%D0%A0%D1%83%D1%81%D1%81%D0%BA%D0%B8%D0%B9-6e7681?style=for-the-badge"></a>
  <a href="README.en.md"><img alt="English" src="https://img.shields.io/badge/lang-English-2f6feb?style=for-the-badge"></a>
  &nbsp;
  <a href="../../releases"><img alt="Releases" src="https://img.shields.io/github/v/release/Barret13666/NavBridge?style=for-the-badge&logo=android&logoColor=white&label=download&color=2ea043"></a>
</p>

# NavBridge

An Android app for an ESP32-based vehicle dashboard. It does four things:

1. Takes the phone's location through `FusedLocationProviderClient` (the same
   API Google Maps itself uses — it picks the best source on its own: real
   GNSS, Wi-Fi or cell towers) and sends it as NMEA sentences (`$GPRMC` +
   `$GPGGA`) over UDP to the dashboard, at the same IP:port the dashboard is
   listening on (10110 by default).
2. Computes routes for the "GO" button on the dashboard's navigation screen —
   entirely offline, through the separately installed **BRouter** app, with no
   server of its own anywhere. The dashboard learns the phone's address
   automatically, from the same GPS/UDP packets; nothing is typed in by hand.
3. Passes on the **turn instructions** BRouter works out alongside the route.
   The dashboard draws the manoeuvre arrow and the distance to it.
4. Searches for addresses for the magnifier button on the navigation screen,
   through the open **Photon** geocoder — online, no key, no sign-up.

Routing, turn instructions and search all share UDP port **10111** and answer
the dashboard's requests automatically for as long as the service is running.

## What's new in this version

- **Settings** (button on the main screen). The routing profile moved there,
  along with the interface language and the turn guidance options. There is
  nothing to save — everything applies immediately, and the service re-reads
  the settings on every request, so a change made while riding takes effect on
  the next turn.
- **About NavBridge** — what it is, what mechanisms it uses, and what else has
  to be installed (BRouter with offline data for your region, first of all).
- **Two interface languages.** On first launch the app takes the phone's
  language; if that is neither Russian nor English, it uses English. After
  that it follows whatever is chosen in Settings. The choice is stored as a
  per-app locale, so on Android 13+ it also shows up in the system's own
  app-language settings.
- **Sound and vibration on turns.** The dashboard sends `TCU1`, the app speaks
  the manoeuvre through TTS, vibrates and/or beeps. The dashboard decides, not
  the phone, so what you hear cannot disagree with the arrow on screen.
  Requires firmware with `TCU1` support; with older firmware everything else
  works as before.

## How the turn cues behave

The small things that are not visible in Settings, and that decide whether
this is pleasant to use while moving.

Cues are declared to the system as **navigation audio**, not as music. Because
of that a Bluetooth helmet headset and a car stereo understand what they are:
they play them through the right channel and **duck** the music for a couple
of seconds instead of stopping it. The music carries on by itself, with
nothing to press.

**A new cue interrupts the old one.** If the previous one is still finishing
and the moment has already moved on, the new one is what matters. Otherwise
you get "in three hundred meters, turn left" while already in the turn.

**Distances are rounded to something speakable.** "In 300 meters", not "in 287
meters" — the exact figure is useless here and takes longer to listen to.

**Vibration differs by meaning:** one long pulse for left, two short for
right, a triple for arrival. That is distinguishable through a jacket without
looking at the screen, and it still works when the music in your ears is
louder than the cue.

**Mirroring to a band or watch.** Every cue is also posted as a notification —
Mi Band, Amazfit and smartwatches know nothing about this app, they simply
repeat the phone's notifications, so this is the only way to get a cue onto
your wrist. The notification is one line: the direction in a single word and
the distance — "Left · 300 m", and at the turn itself "Left · now". Shades
(bear, sharp, keep) collapse into plain "left"/"right": on a band the side is
what matters, and how sharp the turn is you will see from the road anyway. For
a roundabout the exit number is added.

On the phone itself that notification is silent and does not vibrate — the
sound and the vibration above already cover that, and doubling them serves
nobody. The band does its own buzz when it receives the notification, and that
is the one you actually feel with the phone in a pocket. A new cue replaces
the old one rather than stacking up, and it clears itself after about a
minute: a cue is only true while the turn is still ahead of you.

**The "Test cue" button** in Settings plays a real cue through the real path.
That is exactly the point: the volume, the voice and whether the sound
actually reaches the headset would otherwise only be discovered at a junction.

## Routing (BRouter)

The "GO" button on the dashboard's map (tap a point → route) needs the
separately installed **BRouter** app — free, open source, computes routes
completely offline and works anywhere in the world with no server of its own.
NavBridge does not bundle it (it is a separate Google Play / F-Droid app) — it
only asks it for the calculation.

One-time setup:

1. Install **BRouter** — [Google Play](https://play.google.com/store/apps/details?id=btools.routingapp)
   or [F-Droid](https://f-droid.org/packages/btools.routingapp/).
2. Open BRouter, go into its map-data download screen ("Download Manager" /
   "Manage") and download the 5×5° tiles for the areas you will actually be
   riding in (for a city and its region, usually one or two tiles). This is
   not the whole world and not even a whole country — a few megabytes per
   tile, a couple of minutes to fetch. When you travel somewhere new, download
   the tile for it the same way, from the same BRouter screen.
3. In NavBridge, open **Settings** and pick the routing profile (Electric
   transport/bicycle, Car or On foot) — the first one usually suits a personal
   electric vehicle. The profile can be changed while the service is running,
   without restarting it: it is re-read on every request.
4. Nothing else has to be configured by hand — as soon as you press **Start**
   in NavBridge, it listens for route requests from the dashboard (UDP port
   10111) alongside sending coordinates, and answers them through BRouter.

If pressing GO on the dashboard produces an error like "route: position not
mapped in existing datafile", that is BRouter saying it has no downloaded data
for that point: open BRouter and fetch the missing tile. The error "BRouter app
not installed or service unreachable" means the BRouter app is not installed,
or has never been launched (open it at least once after installing).

## Turn instructions

There is nothing to switch on: NavBridge asks BRouter for the instructions
along with the route and forwards them to the dashboard right after the
polyline. Every manoeuvre BRouter distinguishes is supported — turns at three
degrees of sharpness in both directions, U-turns, forks ("keep left/right")
and roundabouts.

The dashboard measures the distance to a turn **along the route itself**, not
in a straight line, and recomputes it on every fix. That is done on the
dashboard deliberately: BRouter reports a distance fixed at the moment the
route was built, and it is correct for exactly the one second in which you
passed the previous manoeuvre.

The commands "continue straight", "beeline between points" and "off route" are
not shown: the first two are noise, and the dashboard detects leaving the
route itself, noticeably sooner.

**The one requirement is the BRouter profile.** Instructions are only
generated if the active profile defines `priorityclassifier`. Current stock
`trekking.brf` and `car-fast.brf` have it; an old downloaded profile may not.
There is nothing to check by hand: after every route the dashboard prints one
of these lines

```
route: 12 turn hints from BRouter
route: no BRouter hints (profile lacks priorityclassifier?)
```

The second means the route was built but there will be no BRouter arrows —
update the profile. The dashboard is not left with nothing even then: it
derives the turns from the geometry of the polyline itself. That is cruder —
roundabouts and forks cannot be told apart that way, and a road bending at a
junction where you are on the main road will show up as a turn that should not
be there.

## Address search (Photon)

**Nothing extra to install.** Search runs through
[Photon](https://photon.komoot.io), an open geocoder over OpenStreetMap data
run by Komoot. No key, no sign-up, no separate app: NavBridge talks to it
directly over HTTPS.

The one requirement is **internet on the phone** at the moment of searching.
That is what separates search from routing: BRouter works offline, Photon does
not. A route already built keeps working when the connection drops, but a new
address will not be found without one.

How to use it:

1. On the dashboard's navigation screen, press the **magnifier** button (top
   left, next to the back arrow).
2. Type an address or a place name. The list appears by itself, a second after
   you stop typing — there is nothing to press. Minimum 3 characters.
3. The keyboard is switched with the buttons on the bottom row:
   - **ABC** — Latin
   - **РУС** — Cyrillic
   - **123** — digits, punctuation and Polish letters (ĄĆĘŁŃÓŚŹŻ)

   The chosen alphabet is remembered between searches and survives a reboot.
   The digit layout is not remembered — that is a quick trip for a house
   number, not a language.
4. Tap the line you want — the panel closes, the map moves to the point and a
   destination marker appears on it. The **OK** key opens the first result in
   the list.
5. From there as usual: press **GO**, and BRouter computes the route.

Search takes your position into account: with identical names, the nearest is
shown first. Case does not matter and diacritics can be skipped — "Wroclaw"
finds "Wrocław".

Places that share a name but are different things (the classic case being a
metro station, a park, an exhibition centre and a district all called the
same) are told apart by NavBridge itself: duplicates of the same place are
dropped, and the rest get a district, street or place type appended so the
lines do not all look alike.

Results the dashboard physically cannot render — Chinese characters, Hangul,
Arabic — are filtered out on the phone and never reach it.

Tapping a result does not start routing automatically. That is deliberate:
picking the wrong option and immediately launching a calculation is worse than
pressing GO one extra time.

If text appears on the dashboard instead of a list, that is the server's
answer: "no results" — nothing found, try differently; "rate limited, wait
15s" — too often, wait; "network: ..." — no internet on the phone; "no
response from phone (app running?)" — the NavBridge service is not running.

## Installing

Download the latest `NavBridge-*.apk` from the
[Releases](../../releases) page, open the file on your phone and install it —
Android will ask you to allow installation "from unknown sources" for whatever
app you opened the file with (Files, a browser, and so on). That is normal for
an APK from outside Google Play.

## Using it

1. Open **NavBridge**.
2. Enter the IP of your ESP32 (the one shown on the navigation screen) and the
   port (`10110` by default, no need to change it). The routing profile, the
   language and the turn cues live in **Settings** (see the sections above).
3. Press **Start** — the app will ask for location permission (grant it), and
   on Android 13+ for notification permission (grant that too, otherwise you
   will not see the persistent notification that the service is running, and
   cues will not reach a band or watch).
4. From then on it works in the background (with a notification carrying a
   **Stop** button), sending coordinates about once a second until you press
   Stop, and answering route, turn-instruction and search requests from the
   dashboard along the way. The phone's screen can be switched off — the
   foreground service keeps running.

## Limitations / things worth knowing

- The app needs **Google Play Services** (for `FusedLocationProviderClient`).
  Ordinary Android phones always have it, but it will not work on degoogled
  builds (GrapheneOS and similar).
- Accuracy depends on what `FusedLocationProviderClient` managed to produce —
  outdoors that is usually GNSS (a few metres), indoors Wi-Fi/cell (from ~30
  to several hundred metres), exactly as in the phone's own maps app.
- Coordinates are sent about once a second. The dashboard is designed
  around that interval: its "link lost" and "fix stale" thresholds depend on
  it, and so does how often the distance to the next turn is refreshed.
- The app is not signed with a release key — it is a debug build, which is
  enough for personal use.
- BRouter routes offline, but it has a limit on the straight-line distance
  between points, around 150 km (computation time grows quadratically with
  distance). For town and regional trips that is a large margin.
- The route is computed on the phone's CPU at the moment you press GO — the
  first time in a new area it can take a few seconds (plus the time BRouter
  needs to read that segment off disk).
- The public Photon server is free and offered with no availability
  guarantees; under heavy load requests are throttled. NavBridge stays within
  reason: the dashboard waits a second of silence after the last keypress and
  does not re-ask what it has already asked, and the app makes at most one
  request every 1.2 seconds. If the server does refuse anyway, NavBridge stops
  contacting it entirely for 15 seconds — knocking again at a closed door is
  what gets you blocked rather than throttled. A trip adds up to dozens of
  requests, not thousands.
- If you would rather not depend on the public server, Photon can be
  self-hosted (Apache-2.0) — then it is enough to change `PHOTON_BASE` in
  `PhotonClient.kt` to your own server's address.
- Search returns at most 8 results; 4 fit on the dashboard's screen.

## License

MIT — see [LICENSE](LICENSE).

Search and routing data from OpenStreetMap,
[ODbL](https://www.openstreetmap.org/copyright).
