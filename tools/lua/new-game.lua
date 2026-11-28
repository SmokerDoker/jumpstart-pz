-- Test tooling (sent through the test jar's Lua server): starts a new single player sandbox game
-- from the main menu, as the menu's New Game and the game's debug scenarios do, so an in-game test
-- needs no click. Returns "OK <save name>" or why not.
local ms = MainScreen and MainScreen.instance
if not ms then return "no main menu" end
if getPlayer() then return "in a game already" end
if not ms.desc then ms.desc = SurvivorFactory.CreateSurvivor() end

-- the mods of the new game: the menu's default list, as the menu's Solo button does
local current = ActiveMods.getById("currentGame")
current:copyFrom(ActiveMods.getById("default"))
-- with the lean main menu they are not loaded yet: the menu's Solo button loads them first (a Lua
-- reset; the fork's LeanMenu). Answers RESET: call again once the menu is back. The global
-- below goes with the reset, so finding it still set means the button did not load them.
if getCore():getOptionModsEnabled() and ActiveMods.requiresResetLua(current) then
    if JST_NewGameClicked then return "the menu's New Game did not load the mods" end
    JST_NewGameClicked = true
    MainScreen.onMenuItemMouseDownMainMenu(ms.survivalOption, 0, 0)
    return "RESET the menu's New Game loads the mods"
end
if ms.soloScreen and ms.soloScreen:isVisible() then ms.soloScreen:setVisible(false) end

getWorld():setGameMode(GameMode.SANDBOX)
ms:setDefaultSandboxVars()
getWorld():setMap("Muldraugh, KY")
local name = "jstest" .. ZombRand(100000) .. ZombRand(100000)
getWorld():setWorld(name)
createWorld(name)

-- where to spawn: the menu's own way (first spawn region), else a street in Muldraugh
local spawned = false
if MapSpawnSelect and MapSpawnSelect.instance and CharacterCreationProfession then
    spawned = pcall(function()
        MapSpawnSelect.instance:useDefaultSpawnRegion()
        MapSpawnSelect.instance:saveGenParams()
        CharacterCreationProfession.initWorld()
    end)
end
if not spawned then
    getWorld():setLuaPlayerDesc(ms.desc)
    getWorld():getLuaTraits():clear()
    getWorld():setLuaPosX(10745)
    getWorld():setLuaPosY(9412)
    getWorld():setLuaPosZ(0)
end

GameWindow.doRenderEvent(false)
forceChangeState(LoadingQueueState.new())
return "OK " .. name .. (spawned and "" or " (fixed spawn)") .. ", mods "
    .. ActiveMods.getById("currentGame"):getMods():size() .. " of " .. ActiveMods.getById("default"):getMods():size()
