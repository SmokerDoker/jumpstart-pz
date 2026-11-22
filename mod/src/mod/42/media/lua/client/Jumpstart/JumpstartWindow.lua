-- Jumpstart's window: Java mods with their state and BiteCheck verdict, and buttons to load them
-- live, switch them off and on, and reload their jar. Opens from the main menu button or Ctrl+J.
require "ISUI/ISCollapsableWindow"
require "ISUI/ISScrollingListBox"
require "ISUI/ISButton"
require "Jumpstart/JumpstartModel"

JumpstartUI = JumpstartUI or {}
local T = JumpstartModel.text
local FONT = UIFont.Small
local PAD = 10
local BTN_H = 25

-- Work for the game's main thread (UI must not be touched from other threads, e.g. tests).
JumpstartUI.queue = JumpstartUI.queue or {}
function JumpstartUI.later(fn)
    table.insert(JumpstartUI.queue, fn)
end
local function runQueue()
    if #JumpstartUI.queue == 0 then return end
    local q = JumpstartUI.queue
    JumpstartUI.queue = {}
    for _, fn in ipairs(q) do
        local ok, err = pcall(fn)
        if not ok then
            JumpstartUI.lastError = tostring(err)
            print("[Jumpstart] UI error: " .. tostring(err))
        end
    end
end

JumpstartWindow = ISCollapsableWindow:derive("JumpstartWindow")

function JumpstartWindow:new(x, y, w, h)
    local o = ISCollapsableWindow.new(self, x, y, w, h)
    o.title = T("title")
    o.mods = {}
    o.message = ""
    o.minimumWidth = 520
    o.minimumHeight = 360
    return o
end

function JumpstartWindow:createChildren()
    ISCollapsableWindow.createChildren(self)
    local top = self:titleBarHeight() + PAD
    local fontH = getTextManager():getFontHeight(FONT)
    self.listTop = top + fontH + 6

    local listH = self.height - self.listTop - (fontH * 5 + 16) - BTN_H - PAD * 2
    self.list = ISScrollingListBox:new(PAD, self.listTop, self.width - PAD * 2, listH)
    self.list:initialise()
    self.list:instantiate()
    self.list.itemheight = fontH + 8
    self.list.font = FONT
    self.list.drawBorder = true
    self.list.doDrawItem = JumpstartWindow.drawRow
    self.list.jumpstart = self
    self.list:setOnMouseDownFunction(self, JumpstartWindow.onSelect)
    self:addChild(self.list)

    self.detailTop = self.listTop + listH + 6

    local y = self.height - BTN_H - PAD
    local x = PAD
    self.buttons = {}
    for _, key in ipairs({ "install", "off", "on", "update", "refresh", "close" }) do
        local label = T(key)
        local w = getTextManager():MeasureStringX(FONT, label) + 20
        local b = ISButton:new(x, y, w, BTN_H, label, self, JumpstartWindow.onButton)
        b.internal = key
        b:initialise()
        b:instantiate()
        self:addChild(b)
        self.buttons[key] = b
        x = x + w + 6
    end
    self:reload()
end

function JumpstartWindow:reload()
    self.ready = JumpstartModel.ready()
    self.mods = self.ready and JumpstartModel.scan() or {}
    local keep = self:selected() and self:selected().id
    self.list:clear()
    for _, mod in ipairs(self.mods) do
        self.list:addItem(mod.name, mod)
        if mod.id == keep then self.list.selected = #self.list.items end
    end
    if self.list.selected > #self.list.items then self.list.selected = 0 end
    self:updateButtons()
end

function JumpstartWindow:selected()
    if not self.list or not self.list.items then return nil end
    local row = self.list.items[self.list.selected]
    return row and row.item or nil
end

function JumpstartWindow:updateButtons()
    local actions = JumpstartModel.actions(self:selected())
    for _, key in ipairs({ "install", "off", "on", "update" }) do
        self.buttons[key]:setEnable(self.ready and actions[key] == true)
    end
    self.buttons.refresh:setEnable(self.ready)
end

function JumpstartWindow:onSelect(mod)
    self:updateButtons()
end

function JumpstartWindow:onButton(button)
    local key = button.internal
    if key == "close" then
        self:close()
        return
    end
    self:run(key)
end

-- one action on the selected mod (also used by tests through JumpstartUI.later)
function JumpstartWindow:run(key)
    local mod = self:selected()
    local result
    if key == "refresh" then
        result = JumpstartModel.refreshIndex()
    elseif mod and JumpstartModel[key] and JumpstartModel.actions(mod)[key] then
        result = JumpstartModel[key](mod)
    else
        return nil
    end
    self.message = result or ""
    self:reload()
    return result
end

function JumpstartWindow:select(id)
    for i, row in ipairs(self.list.items) do
        if row.item.id == id then
            self.list.selected = i
            self:updateButtons()
            return true
        end
    end
    return false
end

local STATE_COLOR = {
    ["on"] = { 0.4, 0.9, 0.4 },
    ["on, until restart"] = { 0.6, 0.8, 0.6 },
    ["off"] = { 0.9, 0.7, 0.3 },
    ["not loaded"] = { 0.7, 0.7, 0.7 },
}

function JumpstartWindow.drawRow(list, y, row, alt)
    local h = list.itemheight
    if list.items[list.selected] == row then
        list:drawRect(0, y, list:getWidth(), h, 0.3, 0.7, 0.35, 0.15)
    end
    list:drawRectBorder(0, y, list:getWidth(), h, 0.2, 1, 1, 1)
    local mod = row.item
    list:drawText(mod.name, 10, y + 4, 1, 1, 1, 0.9, list.font)
    local state = T("state_" .. tostring(mod.state))
    local c = STATE_COLOR[mod.state] or { 1, 1, 1 }
    local w = getTextManager():MeasureStringX(list.font, state)
    list:drawText(state, list:getWidth() - w - 20, y + 4, c[1], c[2], c[3], 1, list.font)
    return y + h
end

function JumpstartWindow:prerender()
    ISCollapsableWindow.prerender(self)
end

function JumpstartWindow:render()
    ISCollapsableWindow.render(self)
    if self.isCollapsed then return end
    local fontH = getTextManager():getFontHeight(FONT)
    local top = self:titleBarHeight() + PAD
    if not self.ready then
        self:drawText(T("notReady") .. JumpstartModel.status(), PAD, top, 1, 0.4, 0.4, 1, FONT)
        return
    end
    if #self.mods == 0 then
        self:drawText(T("noMods"), PAD, top, 1, 1, 1, 0.8, FONT)
    else
        self:drawText(T("luaNote"), PAD, top, 0.8, 0.8, 0.8, 0.8, FONT)
    end

    local y = self.detailTop
    local mod = self:selected()
    if mod then
        local word, detail = JumpstartModel.splitVerdict(JumpstartModel.verdict(mod))
        local c = (word == "ALLOW") and { 0.5, 1, 0.5 } or (word == "REFUSE") and { 1, 0.4, 0.4 } or { 1, 0.8, 0.3 }
        self:drawText(mod.name .. "  [" .. mod.id .. "]", PAD, y, 1, 1, 1, 1, FONT)
        y = y + fontH + 2
        self:drawText(T(word), PAD, y, c[1], c[2], c[3], 1, FONT)
        y = y + fontH + 2
        self:drawText(detail, PAD, y, 0.8, 0.8, 0.8, 1, FONT)
        y = y + fontH + 2
        local jar = mod.jar
        if string.len(jar) > 100 then jar = "..." .. string.sub(jar, -97) end
        self:drawText(jar, PAD, y, 0.6, 0.6, 0.6, 1, FONT)
        y = y + fontH + 2
    end
    if self.message ~= "" then
        local ok = string.sub(self.message, 1, 4) == "DONE" or string.sub(self.message, 1, 2) == "OK"
        self:drawText(self.message, PAD, self.height - BTN_H - PAD - fontH - 4,
            ok and 0.5 or 1, ok and 1 or 0.7, ok and 0.5 or 0.4, 1, FONT)
    end
end

function JumpstartWindow:close()
    self:setVisible(false)
    self:removeFromUIManager()
    JumpstartUI.window = nil
end

function JumpstartUI.open()
    if JumpstartUI.window then
        JumpstartUI.window:reload()
        JumpstartUI.window:bringToTop()
        return JumpstartUI.window
    end
    local w, h = 760, 520
    local x = math.floor((getCore():getScreenWidth() - w) / 2)
    local y = math.floor((getCore():getScreenHeight() - h) / 2)
    local win = JumpstartWindow:new(x, y, w, h)
    win:initialise()
    win:addToUIManager()
    JumpstartUI.window = win
    return win
end

function JumpstartUI.toggle()
    if JumpstartUI.window then
        JumpstartUI.window:close()
    else
        JumpstartUI.open()
    end
end

-- main menu button (top right)
local function addMenuButton()
    if JumpstartUI.menuButton then return end
    local label = "Jumpstart"
    local w = getTextManager():MeasureStringX(UIFont.Medium, label) + 30
    local b = ISButton:new(getCore():getScreenWidth() - w - 20, 20, w, 30, label, nil, function() JumpstartUI.toggle() end)
    b.font = UIFont.Medium
    b:initialise()
    b:instantiate()
    b:addToUIManager()
    JumpstartUI.menuButton = b
end

local function removeMenuButton()
    if JumpstartUI.menuButton then
        JumpstartUI.menuButton:setVisible(false)
        JumpstartUI.menuButton:removeFromUIManager()
        JumpstartUI.menuButton = nil
    end
end

local function onKeyPressed(key)
    if key == Keyboard.KEY_J and isCtrlKeyDown() then
        JumpstartUI.toggle()
    end
end

Events.OnMainMenuEnter.Add(addMenuButton)
Events.OnGameStart.Add(removeMenuButton)
Events.OnKeyPressed.Add(onKeyPressed)
-- OnRenderTick runs on the main thread every frame, at the main menu (where OnTick and OnFETick do
-- not fire in Build 42) and in game
Events.OnRenderTick.Add(runQueue)
-- Lua was (re)loaded outside a game, e.g. at the main menu after changing mods: add the button on
-- the next frame, since OnMainMenuEnter does not come again then
if getPlayer() == nil then
    JumpstartUI.later(addMenuButton)
end
