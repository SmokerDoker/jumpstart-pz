-- Jumpstart: what the window shows and does, without any UI, so it can be tested on its own.
-- Every decision that matters for safety is made in Java (BiteCheck, the native confirmation);
-- this file only lists mods and forwards button presses.

JumpstartModel = JumpstartModel or {}

-- mods that run Jumpstart itself; switching them live makes no sense
local SKIP = { ZombieBuddy = true, Jumpstart = true }

local function lang()
    local ok, name = pcall(function() return tostring(Translator.getLanguage()) end)
    return (ok and name and string.find(name, "DE", 1, true)) and "DE" or "EN"
end

local TEXT = {
    EN = {
        title = "Jumpstart - Java mods live",
        notReady = "Jumpstart is not available: ",
        noMods = "No Java mods found in your mod folders.",
        install = "Load live", off = "Switch off", on = "Switch on", update = "Reload jar",
        refresh = "Update BiteCheck index", close = "Close",
        state_on = "on", state_off = "off", ["state_not loaded"] = "not loaded",
        ["state_on, until restart"] = "on (until restart)", state_unknown = "?",
        ALLOW = "BiteCheck: allowed, loads without asking",
        CONFIRM = "BiteCheck: unknown file, you will be asked once",
        CONFIRM_TWICE = "BiteCheck: suspicious or flagged, you will be asked twice",
        REFUSE = "BiteCheck: blocked, will not be loaded",
        luaNote = "Only the Java part loads live; Lua files of the mod load after enabling it in the mod menu.",
        working = "Working...",
    },
    DE = {
        title = "Jumpstart - Java-Mods live",
        notReady = "Jumpstart ist nicht bereit: ",
        noMods = "Keine Java-Mods in deinen Mod-Ordnern gefunden.",
        install = "Live laden", off = "Ausschalten", on = "Einschalten", update = "Jar neu laden",
        refresh = "BiteCheck-Index aktualisieren", close = "Schliessen",
        state_on = "an", state_off = "aus", ["state_not loaded"] = "nicht geladen",
        ["state_on, until restart"] = "an (bis Neustart)", state_unknown = "?",
        ALLOW = "BiteCheck: erlaubt, laedt ohne Rueckfrage",
        CONFIRM = "BiteCheck: unbekannte Datei, du wirst einmal gefragt",
        CONFIRM_TWICE = "BiteCheck: verdaechtig oder markiert, du wirst zweimal gefragt",
        REFUSE = "BiteCheck: gesperrt, wird nicht geladen",
        luaNote = "Live laedt nur der Java-Teil; Lua-Dateien der Mod kommen nach dem Aktivieren im Mod-Menue.",
        working = "Moment...",
    },
}

function JumpstartModel.text(key)
    local t = TEXT[lang()] or TEXT.EN
    return t[key] or TEXT.EN[key] or key
end

-- "ready", or why not (also when the Java part is missing entirely)
function JumpstartModel.status()
    if not Jumpstart then
        return "Jumpstart's Java part is not loaded (ZombieBuddy missing or the jar not approved)"
    end
    return Jumpstart.status()
end

function JumpstartModel.ready()
    return JumpstartModel.status() == "ready"
end

-- all Java mods in the mod folders: { id, name, jar, pkg, state }
function JumpstartModel.scan()
    local mods = {}
    if not Jumpstart then return mods end
    local seen = {}
    local dirs = getModDirectoryTable()
    for _, dir in pairs(dirs) do
        local ok, info = pcall(getModInfo, dir)
        if ok and info then
            local id = info:getId()
            if id and not SKIP[id] and not seen[id] then
                local common, version = info:getCommonDir(), info:getVersionDir()
                local java = (common and version) and Jumpstart.javaModOf(common, version) or ""
                local tab = string.find(java, "\t", 1, true)
                if tab then
                    seen[id] = true
                    table.insert(mods, {
                        id = id,
                        name = info:getName() or id,
                        jar = string.sub(java, 1, tab - 1),
                        pkg = string.sub(java, tab + 1),
                        state = Jumpstart.stateOf(id),
                    })
                end
            end
        end
    end
    table.sort(mods, function(a, b) return string.lower(a.name) < string.lower(b.name) end)
    return mods
end

function JumpstartModel.refreshState(mod)
    mod.state = Jumpstart.stateOf(mod.id)
end

-- BiteCheck's verdict for the mod's jar, cached per jar until the index is refreshed
local verdicts = {}
function JumpstartModel.verdict(mod)
    if not verdicts[mod.jar] then
        verdicts[mod.jar] = Jumpstart.check(mod.jar)
    end
    return verdicts[mod.jar]
end

-- "ALLOW VERIFIED (info) sha256 ..." -> "ALLOW", "VERIFIED (info)"
function JumpstartModel.splitVerdict(line)
    local space = string.find(line, " ", 1, true)
    if not space then return line, "" end
    local word = string.sub(line, 1, space - 1)
    local rest = string.sub(line, space + 1)
    local sha = string.find(rest, " sha256 ", 1, true)
    if sha then rest = string.sub(rest, 1, sha - 1) end
    return word, rest
end

-- which buttons make sense for a state
function JumpstartModel.actions(mod)
    if not mod then return {} end
    local s = mod.state
    if s == "not loaded" then return { install = true } end
    if s == "on" then return { off = true, update = true } end
    if s == "off" then return { on = true, update = true } end
    return {}
end

-- the button presses; each returns Java's one-line result
function JumpstartModel.install(mod)
    local r = Jumpstart.install(mod.jar, mod.pkg, mod.id)
    verdicts[mod.jar] = nil -- an approval changes the verdict to ALLOW
    JumpstartModel.refreshState(mod)
    return r
end

function JumpstartModel.update(mod)
    local r = Jumpstart.update(mod.jar, mod.pkg, mod.id)
    verdicts[mod.jar] = nil
    JumpstartModel.refreshState(mod)
    return r
end

function JumpstartModel.off(mod)
    local r = Jumpstart.off(mod.id)
    JumpstartModel.refreshState(mod)
    return r
end

function JumpstartModel.on(mod)
    local r = Jumpstart.on(mod.id)
    JumpstartModel.refreshState(mod)
    return r
end

function JumpstartModel.refreshIndex()
    verdicts = {}
    return Jumpstart.refreshIndex()
end
