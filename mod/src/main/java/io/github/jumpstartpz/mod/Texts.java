package io.github.jumpstartpz.mod;

import java.util.Map;

/** The secure screen's texts in German and English. */
final class Texts {
    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("hint", "Jumpstart: Ctrl+Shift+J"),
            Map.entry("title", "Jumpstart"),
            Map.entry("secure", "Secure screen: drawn by Jumpstart in Java and used only with your real keyboard. Lua mods can neither open nor control it."),
            Map.entry("help", "Up/Down: select   Enter: load live / reload   A: on/off   R: update BiteCheck index   Esc: close"),
            Map.entry("noMods", "No Java mods in your mod folders."),
            Map.entry("state.on", "on"),
            Map.entry("state.off", "off"),
            Map.entry("state.not loaded", "not loaded"),
            Map.entry("state.on, until restart", "on (until restart)"),
            Map.entry("ALLOW", "BiteCheck: allowed, loads without asking"),
            Map.entry("CONFIRM", "BiteCheck: unknown file, you will be asked once"),
            Map.entry("CONFIRM_TWICE", "BiteCheck: suspicious or flagged, you will be asked twice"),
            Map.entry("REFUSE", "BiteCheck: blocked, will not be loaded"),
            Map.entry("luaNote", "Live loading covers the Java part. The mod's Lua files come after enabling it in the mod menu."),
            Map.entry("confirm1.title", "Load a file BiteCheck does not know?"),
            Map.entry("confirm1.text", "BiteCheck has not checked this exact file. Java mods can do anything on your computer. Only load it if you trust where it comes from."),
            Map.entry("confirm2.title", "Are you really sure?"),
            Map.entry("confirm2.flagged", "The BiteCheck registry has FLAGGED this file."),
            Map.entry("confirm2.suspicious", "BiteCheck's scanner found code that is typical for malware, for example starting programs or downloading files."),
            Map.entry("confirm.wait", "J = load (possible in %d s)   N or Esc = cancel"),
            Map.entry("confirm.ready", "J = load   N or Esc = cancel"),
            Map.entry("cancelled", "Cancelled, nothing was loaded."),
            Map.entry("refused", "BiteCheck blocks this file; it was not loaded."),
            Map.entry("cannotCheck", "Cannot check the file: "),
            Map.entry("checking", "Checking with BiteCheck..."),
            Map.entry("file", "File: "),
            Map.entry("mod", "Mod: "));

    private static final Map<String, String> DE = Map.ofEntries(
            Map.entry("hint", "Jumpstart: Strg+Umschalt+J"),
            Map.entry("secure", "Sicherer Bildschirm: von Jumpstart in Java gezeichnet und nur mit deiner echten Tastatur bedienbar. Lua-Mods können ihn weder öffnen noch bedienen."),
            Map.entry("help", "Hoch/Runter: auswählen   Enter: live laden / neu laden   A: an/aus   R: BiteCheck-Index aktualisieren   Esc: schließen"),
            Map.entry("noMods", "Keine Java-Mods in deinen Mod-Ordnern."),
            Map.entry("state.on", "an"),
            Map.entry("state.off", "aus"),
            Map.entry("state.not loaded", "nicht geladen"),
            Map.entry("state.on, until restart", "an (bis Neustart)"),
            Map.entry("ALLOW", "BiteCheck: erlaubt, lädt ohne Rückfrage"),
            Map.entry("CONFIRM", "BiteCheck: unbekannte Datei, du wirst einmal gefragt"),
            Map.entry("CONFIRM_TWICE", "BiteCheck: verdächtig oder markiert, du wirst zweimal gefragt"),
            Map.entry("REFUSE", "BiteCheck: gesperrt, wird nicht geladen"),
            Map.entry("luaNote", "Live geladen wird der Java-Teil. Die Lua-Dateien der Mod kommen nach dem Aktivieren im Mod-Menü."),
            Map.entry("confirm1.title", "Eine Datei laden, die BiteCheck nicht kennt?"),
            Map.entry("confirm1.text", "BiteCheck hat genau diese Datei nicht geprüft. Java-Mods können alles auf deinem Computer tun. Lade sie nur, wenn du der Quelle vertraust."),
            Map.entry("confirm2.title", "Bist du wirklich sicher?"),
            Map.entry("confirm2.flagged", "Die BiteCheck-Registry hat diese Datei MARKIERT."),
            Map.entry("confirm2.suspicious", "BiteChecks Scanner hat Code gefunden, der typisch für Schadsoftware ist, zum Beispiel Programme starten oder Dateien herunterladen."),
            Map.entry("confirm.wait", "J = laden (möglich in %d s)   N oder Esc = abbrechen"),
            Map.entry("confirm.ready", "J = laden   N oder Esc = abbrechen"),
            Map.entry("cancelled", "Abgebrochen, nichts wurde geladen."),
            Map.entry("refused", "BiteCheck sperrt diese Datei; sie wurde nicht geladen."),
            Map.entry("cannotCheck", "Datei kann nicht geprüft werden: "),
            Map.entry("checking", "Prüfe mit BiteCheck..."),
            Map.entry("file", "Datei: "));

    private final boolean german;

    Texts(String language) {
        german = language != null && language.toUpperCase(java.util.Locale.ROOT).startsWith("DE");
    }

    String get(String key) {
        String text = german ? DE.get(key) : null;
        if (text == null) {
            text = EN.get(key);
        }
        return text == null ? key : text;
    }
}
