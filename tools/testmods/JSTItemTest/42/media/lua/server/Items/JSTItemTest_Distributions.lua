-- Jumpstart live data test: the test token in random junk crates (only new crates get it).
local list = ProceduralDistributions and ProceduralDistributions.list and ProceduralDistributions.list.CrateRandomJunk
if list and list.items then
    table.insert(list.items, "JSTItemTest.TestToken")
    table.insert(list.items, 50)
    print("[JSTItemTest] test token added to CrateRandomJunk")
else
    print("[JSTItemTest] CrateRandomJunk not found")
end
