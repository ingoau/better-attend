import XCTest

/// Drives the People list and Participant detail against the demo backend. Set
/// `TEST_RUNNER_ATTEND_SHOT_DIR=/some/dir` on `xcodebuild test` to also save screenshots there.
final class PeopleUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testBrowseFilterAndOpenDetail() {
        let app = XCUIApplication()
        app.launchArguments += ["-AttendDemo", "YES", "-AttendOpenURL", "attend://people"]
        app.launch()

        // Summary and chips.
        XCTAssertTrue(app.staticTexts["of 121 here"].waitForExistence(timeout: 15))
        shot("list")

        // Quick filter: needs attention.
        app.buttons["5 Need attention"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Leo Nguyen"].waitForExistence(timeout: 5))
        shot("list-attention")

        // Detail for Leo, then page to the next person.
        app.staticTexts["Leo Nguyen"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Anaphylaxis risk"].waitForExistence(timeout: 10))
        shot("detail")
        app.swipeUp()
        shot("detail-scrolled")
        app.swipeUp()
        app.swipeUp()
        shot("detail-bottom")
        let next = app.buttons["Next Person"]
        XCTAssertTrue(next.exists)
        next.tap()
        XCTAssertTrue(app.staticTexts["4 of 5"].waitForExistence(timeout: 5))
        shot("detail-next")

        // Back to the list, open the filter sheet.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["Filters and sort"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Filter & Sort"].waitForExistence(timeout: 5))
        shot("filters")
    }

    @MainActor
    func testSearchAndAddNote() {
        let app = XCUIApplication()
        app.launchArguments += ["-AttendDemo", "YES", "-AttendOpenURL", "attend://people"]
        app.launch()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 15))
        search.tap()
        search.typeText("priya")
        let priya = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Priya'")).firstMatch
        XCTAssertTrue(priya.waitForExistence(timeout: 5))
        shot("search")
        priya.tap()
        XCTAssertTrue(app.buttons["More"].waitForExistence(timeout: 10))
        let add = app.buttons["Add Note"]
        for _ in 0..<8 where !add.isHittable { app.swipeUp() }
        add.tap()
        XCTAssertTrue(app.staticTexts["New Note"].waitForExistence(timeout: 5))
        app.typeText("Lost her lanyard, gave a spare.")
        shot("note-sheet")
        app.buttons["Add"].tap()
        XCTAssertTrue(app.staticTexts["Lost her lanyard, gave a spare."].waitForExistence(timeout: 5))
        shot("note-added")
    }

    @MainActor
    private func shot(_ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["ATTEND_SHOT_DIR"] else { return }
        let url = URL(fileURLWithPath: dir).appending(path: "ui-\(name).png")
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: url)
    }
}
