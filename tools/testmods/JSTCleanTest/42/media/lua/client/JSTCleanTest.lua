-- Test mod for jumpstart-pz tools/cleanremovaltest.ps1: everything Jumpstart should take back when
-- the mod is switched off (and give back when it comes on), or archive when it is uninstalled.

JST_Marker = true -- a new global

JSTCleanTest = { ticks = 0 } -- a new table; the tick counter lives here, not in a global set later

local original = luautils.stringStarts
JSTCleanTest.gameStringStarts = original -- for the tests: the game's function, to compare after switching off
luautils.stringStarts = function(s, prefix) -- replaces a game function
    return original(s, prefix)
end

JSTPanel = ISPanel:derive("JSTPanel") -- a window class of its own

function JSTPanel:prerender()
    ISPanel.prerender(self)
end

local function onTick()
    JSTCleanTest.ticks = JSTCleanTest.ticks + 1
end
Events.OnTick.Add(onTick)

local function onGameStart()
    local panel = JSTPanel:new(100, 100, 220, 80)
    panel:initialise()
    panel:addToUIManager()
    JSTCleanTest.panel = panel

    local writer = getFileWriter("jstcleantest.txt", true, false)
    writer:write("written by JSTCleanTest")
    writer:close()

    local data = ModData.getOrCreate("JSTest")
    data.value = 42
end
Events.OnGameStart.Add(onGameStart) -- a live load gets the start events it missed
