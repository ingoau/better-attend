import XCTest

/// Organizer tools against the demo backend: inviting a walk-in and the event staff screen. Set
/// `ATTEND_SHOT_DIR=/some/dir` (TEST_RUNNER_ prefixed on `xcodebuild test`) to save screenshots.
final class OrganizerUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testInviteWalkInAndOpenThem() {
        let app = launch("attend://people")
        let invite = app.navigationBars.buttons["Invite"]
        XCTAssertTrue(invite.waitForExistence(timeout: 15))
        invite.tap()

        let email = app.textFields["invite-email"]
        XCTAssertTrue(email.waitForExistence(timeout: 5))
        email.tap()
        email.typeText("river.walkin@example.com")
        let first = app.textFields["invite-first-name"]
        first.tap()
        first.typeText("River")
        shot("invite-sheet")
        app.buttons["invite-send"].tap()

        XCTAssertTrue(app.staticTexts["Invitation Sent"].waitForExistence(timeout: 10))
        shot("invite-sent")
        app.buttons["View River"].tap()
        XCTAssertTrue(app.buttons["More"].waitForExistence(timeout: 10))
        XCTAssertTrue(element(app, containing: "River").waitForExistence(timeout: 10))
        shot("invite-detail")
    }

    @MainActor
    func testInvitingSomeoneAlreadyRegisteredShowsTheServerMessage() {
        let app = launch("attend://people")
        let invite = app.navigationBars.buttons["Invite"]
        XCTAssertTrue(invite.waitForExistence(timeout: 15))
        invite.tap()
        let email = app.textFields["invite-email"]
        XCTAssertTrue(email.waitForExistence(timeout: 5))
        email.tap()
        email.typeText("sam.lee0@example.com") // demo person 0, Sam Lee
        app.buttons["invite-send"].tap()
        XCTAssertTrue(element(app, containing: "already registered for this event").waitForExistence(timeout: 10))
        shot("invite-duplicate")
    }

    @MainActor
    func testEventStaffShowsSeriesAndGlobalAdmins() {
        let app = launch("attend://settings")
        let staff = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Event Staff'")).firstMatch
        XCTAssertTrue(staff.waitForExistence(timeout: 15))
        staff.tap()
        XCTAssertTrue(element(app, containing: "Jamie Chen").waitForExistence(timeout: 10))
        XCTAssertTrue(element(app, containing: "From series").exists)
        XCTAssertTrue(element(app, containing: "Global admin").exists)
        shot("staff")
    }

    /// Any element whose label contains `text` (rows combine their children into one label).
    @MainActor
    private func element(_ app: XCUIApplication, containing text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    @MainActor
    private func launch(_ link: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-AttendDemo", "YES", "-AttendOpenURL", link]
        app.launch()
        return app
    }

    @MainActor
    private func shot(_ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["ATTEND_SHOT_DIR"] else { return }
        let url = URL(fileURLWithPath: dir).appending(path: "ui-\(name).png")
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: url)
    }
}
