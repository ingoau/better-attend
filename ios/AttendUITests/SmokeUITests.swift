import XCTest

/// Boots the real app against the demo backend and checks it comes up signed in.
final class SmokeUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testLaunchesIntoOrganizerTabs() {
        let app = XCUIApplication()
        app.launchArguments += ["-AttendDemo", "YES"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Home"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.tabBars.buttons["Scan"].exists)
    }
}
