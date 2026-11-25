# Jumpstart + BiteCheck: Online-Test mit Freunden

Für 2–4 Spieler auf Windows, macOS und Ubuntu. Dauer: etwa eine Stunde.

## 1. Einmal pro Person installieren

Alle brauchen dieselbe Project-Zomboid-Version (Build 42). Steam muss laufen.

**Am einfachsten: Installer mit einem Klick** von
<https://github.com/SmokerDoker/jumpstart-pz/releases/tag/test-kit> laden und öffnen:

- Windows: `Jumpstart-windows-x64.msi` (SmartScreen: „Weitere Informationen" > „Trotzdem ausführen")
- macOS: `Jumpstart-macos-arm64.dmg` (Apple Silicon) bzw. `Jumpstart-macos-x64.dmg` (Intel); beim
  ersten Öffnen Systemeinstellungen > Datenschutz & Sicherheit > „Trotzdem öffnen"
- Linux: `Jumpstart-linux-x64.deb` bzw. `.rpm`; Steam Deck: `JumpstartSetup.jar` mit der Java des
  Spiels starten (`…/ProjectZomboid/projectzomboid/jre64/bin/java -jar JumpstartSetup.jar`)

Im Fenster **Jumpstart installieren** drücken. Auf macOS/Linux muss Steam für die Startoption kurz
zu sein: Der Installer bietet an, Steam zu schließen, oder legt die Zeile in die Zwischenablage.

Der Installer legt **eine** Datei ins Spielverzeichnis: `Jumpstart.jar` (der ZombieBuddy-Fork mit
Jumpstart darin), gestartet mit `-javaagent:Jumpstart.jar=hotswap=true` (Windows in
`ProjectZomboid64.json`, macOS/Linux in den Steam-Startoptionen). Dazu kommt der Mod-Ordner
`Zomboid/mods/ZombieBuddy` (ohne Jar), im Mod-Menü eingeschaltet. Eine eigene ZombieBuddy.jar wird
als `ZombieBuddy.jar.before-jumpstart` aufgehoben. **ZombieBuddy muss nicht mehr im Workshop
abonniert werden**; ein vorhandenes Abo stört nicht.

Dann das Spiel starten. Wer den BiteCheck-Launcher benutzt: er fragt einmal nach `Jumpstart.jar`
(noch nicht von BiteCheck geprüft) und braucht zweimal Ja. **Strg+Umschalt+J** öffnet den sicheren
Bildschirm von Jumpstart.

Rückgängig machen: im Installer **Entfernen** (oder `JumpstartSetup.jar --uninstall`).

## 2. Erst allein prüfen (jeder, 5 Minuten)

1. Hauptmenü: Strg+Umschalt+J. Öffnet sich der Bildschirm? Tab wechselt die Reiter.
2. Reiter **Java-Bibliothek**: „fps“ tippen, Enter. Kommen Ergebnisse (z. B. Zed's Better FPS)?
3. Reiter **Lua-Bibliothek**: Enter ohne Text. Kommen die beliebtesten Lua-Mods?
4. Esc schließt den Bildschirm; danach muss das Spiel normal auf Tasten reagieren.

**macOS bitte besonders beobachten:** Jumpstart liest die Tastatur direkt über GLFW. Falls das
Spiel beim Drücken von Strg+Umschalt+J abstürzt oder nichts passiert, sofort melden (mit `console.txt`).

## 3. Zusammen spielen

**Host (Windows):** Hauptmenü → *Host* → Mods: ZombieBuddy und eine Java-Mod aus dem
Workshop, die **nicht** von BiteCheck geprüft ist (fast alle der 728 in der Java-Bibliothek),
damit jeder BiteChecks Fragen sieht. Die Freunde treten über die Steam-Einladung bei.
Der gehostete Server bekommt Jumpstart.jar automatisch.

Jeder öffnet nach dem Beitreten Strg+Umschalt+J → Reiter **Server**:
- Stehen dort die Java-Mods des Servers mit „gleich wie bei dir“?
- Der Host sieht zusätzlich „Du bist Admin auf diesem Server“.

## 4. Nur der Admin schaltet (der eigentliche Test)

Im Multiplayer kann niemand allein Java-Mods laden, aus- oder einschalten, auch der Admin nicht:
Im Reiter **Installiert** und in den Bibliotheken kommt dann „Im Multiplayer schaltet nur ein
Admin…“. Bitte bei jedem einmal ausprobieren.

Der Host (Admin) wählt im Reiter **Server** die Java-Mod: **Enter** = Version des Servers für alle
laden, **A** = für alle aus- bzw. einschalten. Dann bei allen anderen:

1. Das Spiel pausiert, der Jumpstart-Bildschirm geht auf: „Admin … ändert eine Java-Mod für alle“
   mit Restzeit (3 Minuten). **J** (nach 2 Sekunden) = einverstanden, **N** = nein.
2. Beim Laden findet Jumpstart genau die Version des Servers. Ist sie nicht von BiteCheck geprüft,
   fragt der Bildschirm zusätzlich (J erst nach 3 Sekunden). Antworten:
   - Spieler A: **J** → lädt, „warte auf die anderen Spieler“.
   - Spieler B: **N** → wird am Ende vom Server getrennt (Grund: „the player said no“).
   - Spieler C: gar nicht antworten → nach 3 Minuten getrennt („not ready in time“).
3. Wenn alle geantwortet haben (oder die Zeit um ist), lädt der Server die Mod selbst, der
   Bildschirm schließt sich, das Spiel läuft weiter.

Danach einmal ohne N/Warten wiederholen: Alle drücken J → niemand wird getrennt. Und einmal **A**
(ausschalten für alle) und wieder **A** (einschalten).

## 5. Was ihr mir schickt

- Was ihr gesehen habt, pro Schritt kurz (gerne Screenshots vom Jumpstart-Bildschirm).
- Bei Problemen `console.txt`: Windows `%USERPROFILE%\Zomboid\console.txt`, macOS/Ubuntu
  `~/Zomboid/console.txt`. Vom Host zusätzlich die Server-Logdatei aus `Zomboid\Logs`.
- Zeilen mit `[Jumpstart]` und `[ZB]` sind die wichtigen.

## Bekannte Grenzen dieser Testversion

- Der Lua-Teil einer Mod wird nicht live geladen, nur der Java-Teil.
- Die Pause ist dieselbe wie beim Speichern des Servers: Die Clients halten an. Ob die Welt auf dem
  Server dabei ganz stillsteht, ist Teil des Tests.
- Ein Update holt genau die Datei, die auf dem Server liegt. Beim Hosten ist das die Datei auf dem
  PC des Hosts; ein Workshop-Update holt der Host vorher im Marktplatz („Update verfügbar“).
