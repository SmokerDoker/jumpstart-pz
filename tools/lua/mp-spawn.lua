-- Test tooling (sent through the test jar's Lua server): after joining a server as a new player,
-- skips the character creation and the spawn choice the way the menu's Next button ends them, so an
-- in-game multiplayer test needs no click. Returns "OK" or why not.
local ms = MainScreen and MainScreen.instance
if not ms then return "no main menu" end
if getPlayer() then return "in a game already" end
if not ms.desc then ms.desc = SurvivorFactory.CreateSurvivor() end

local spawned = false
if MapSpawnSelect and MapSpawnSelect.instance and CharacterCreationProfession then
    spawned = pcall(function()
        if not MapSpawnSelect.instance.selectedRegion then MapSpawnSelect.instance:useDefaultSpawnRegion() end
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
for _, ui in ipairs({ms.charCreationProfession, ms.charCreationMain, MapSpawnSelect and MapSpawnSelect.instance, WorldSelect and WorldSelect.instance}) do
    if ui and ui.setVisible then ui:setVisible(false) end
end

GameWindow.doRenderEvent(false)
forceChangeState(LoadingQueueState.new())
return "OK" .. (spawned and "" or " (fixed spawn)")
