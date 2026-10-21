// Key positions against Apple's keyboard, run in CI with:
// swiftc Keyboard/KeyMetrics.swift Tests/Keys/main.swift -o key-tests && ./key-tests
import Foundation

var failed = 0
func check(_ got: CGFloat, _ want: CGFloat, _ what: String) {
    if abs(got - want) > 0.05 {
        failed += 1
        print("FAIL \(what): \(got), want \(want)")
    }
}

// Pro Max upright (430 pt), as on the user's iPhone with iOS 26: Apple's keys are 45 pt high and
// 36.3 wide, 56 pt apart; the q row's top is 218 pt above our view's bottom (293 above the screen's).
let proMax = KeyMetrics.phone(width: 430, homeButton: false)
let rows = proMax.rows(in: proMax.area)
check(proMax.keyHeight, 45, "Pro Max key height")
check(rows[1].top - rows[0].top, 56, "Pro Max row pitch")
check(proMax.area - rows[0].top, 218, "Pro Max q row top above the view bottom")
check(proMax.area - (rows[3].top + rows[3].height), 5, "Pro Max space under the bottom row")
check(proMax.letterWidth(in: 430), 36.27, "Pro Max key width")
check(proMax.sideMargin, 6.67, "Pro Max side margin")

// iPhone 17 (402 pt): rows 54 apart, q row top 201 pt above the view bottom, bottom keys at the bottom.
let regular = KeyMetrics.phone(width: 402, homeButton: false)
let regularRows = regular.rows(in: regular.area)
check(regular.keyHeight, 43, "iPhone 17 key height")
check(regularRows[1].top - regularRows[0].top, 54, "iPhone 17 row pitch")
check(regular.area - regularRows[0].top, 201, "iPhone 17 q row top above the view bottom")
check(regular.area, 212, "iPhone 17 key area")

// On its side: 32 pt keys, 40 apart.
let side = KeyMetrics.phone(width: 932, homeButton: false)
check(side.keyHeight, 32, "landscape key height")
check(side.area, 162, "landscape key area")

// "Cao hơn một chút": every key 3 pt taller, gaps unchanged; the rows grow up from the bottom.
let taller = KeyMetrics.phone(width: 430, homeButton: false, extra: 3)
let tallerRows = taller.rows(in: taller.area)
check(taller.keyHeight, 48, "taller key height")
check(taller.area, proMax.area + 12, "taller key area")
check(tallerRows[1].top - (tallerRows[0].top + tallerRows[0].height), 11, "taller row gap")

// A view taller than asked keeps the keys at the bottom: the room goes above the first row.
let roomy = proMax.rows(in: proMax.area + 20)
check(roomy[3].top, rows[3].top + 20, "extra height goes above the keys")

print(failed == 0 ? "Keys: all checks passed" : "Keys: \(failed) failed")
exit(failed == 0 ? 0 : 1)
