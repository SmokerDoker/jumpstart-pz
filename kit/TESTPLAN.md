# Jumpstart + BiteCheck: Online-Test mit Freunden

Für 2–4 Spieler auf Windows, macOS und Ubuntu. Dauer: etwa eine Stunde.

## 1. Einmal pro Person installieren

Alle brauchen dieselbe Project-Zomboid-Version (Build 42) und ZombieBuddy.

| | Windows | macOS | Ubuntu |
| --- | --- | --- | --- |
| ZombieBuddy | im Workshop abonnieren **und** einmal `ZombieBuddyInstaller.exe` ausführen | im Workshop abonnieren | im Workshop abonnieren |
| Test-Kit | `powershell -ExecutionPolicy Bypass -File install-windows.ps1` | `bash install-unix.sh` | `bash install-unix.sh` |
| Steam-Startoptionen | nichts (das Skript stellt `hotswap=true` ein) | `-javaagent:ZombieBuddy.jar=hotswap=true --` | `-javaagent:ZombieBuddy.jar=hotswap=true --` |

Dann das Spiel starten. Im Mod-Menü **ZombieBuddy** und **Jumpstart** aktivieren. ZombieBuddy
fragt einmal, ob `Jumpstart.jar` laden darf: **Ja**. Danach öffnet **Strg+Umschalt+J** den
sicheren Bildschirm von Jumpstart (oben rechts im Hauptmenü steht der Hinweis).

Optional: den BiteCheck-Launcher installieren (<https://github.com/SmokerDoker/bitecheck-pz/releases>).

Rückgängig machen: `install-windows.ps1 -Uninstall` bzw. `bash install-unix.sh --uninstall`
(und die Startoption wieder entfernen).

## 2. Erst allein prüfen (jeder, 5 Minuten)

1. Hauptmenü: Strg+Umschalt+J. Öffnet sich der Bildschirm? Tab wechselt die Reiter.
2. Reiter **Java-Bibliothek**: „fps“ tippen, Enter. Kommen Ergebnisse (z. B. Zed's Better FPS)?
3. Reiter **Lua-Bibliothek**: Enter ohne Text. Kommen die beliebtesten Lua-Mods?
4. Esc schließt den Bildschirm; danach muss das Spiel normal auf Tasten reagieren.

**macOS bitte besonders beobachten:** Jumpstart liest die Tastatur direkt über GLFW. Falls das
Spiel beim Drücken von Strg+Umschalt+J abstürzt oder nichts passiert, sofort melden (mit `console.txt`).

## 3. Zusammen spielen

**Host (Windows):** Hauptmenü → *Host* → Mods: ZombieBuddy, Jumpstart und eine Java-Mod aus dem
Workshop, die **nicht** von BiteCheck geprüft ist (fast alle der 728 in der Java-Bibliothek),
damit jeder BiteChecks Fragen sieht. Die Freunde treten über die Steam-Einladung bei.
Der gehostete Server bekommt ZombieBuddy jetzt automatisch (Jumpstart-Fork).

Jeder öffnet nach dem Beitreten Strg+Umschalt+J → Reiter **Server**:
- Stehen dort die Java-Mods des Servers mit „gleich wie bei dir“?
- Der Host sieht zusätzlich „Du bist Admin auf diesem Server“.

## 4. Gemeinsames Update (der eigentliche Test)

Der Host wählt im Reiter **Server** die Java-Mod und drückt **Enter**. Dann bei allen:

1. Das Spiel pausiert, der Jumpstart-Bildschirm geht auf und zeigt „Server-Update“ mit Restzeit (3 Minuten).
2. Jumpstart findet genau die Version des Servers. Ist sie nicht von BiteCheck geprüft, fragt der
   Bildschirm (J erst nach 3 Sekunden). Antworten:
   - Spieler A: **J** → lädt, „warte auf die anderen Spieler“.
   - Spieler B: **N** → wird am Ende vom Server getrennt (Grund: „the player said no“).
   - Spieler C: gar nicht antworten → nach 3 Minuten getrennt („not ready in time“).
3. Wenn alle geantwortet haben (oder die Zeit um ist), lädt der Server die Mod selbst, der
   Bildschirm schließt sich, das Spiel läuft weiter.

Danach einmal ohne N/Warten wiederholen: Alle drücken J → niemand wird getrennt.

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
