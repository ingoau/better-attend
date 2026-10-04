import Foundation
import Testing
@testable import Attend

extension Fixtures {
    static let checkIn = ScanContextRef(id: "c1", name: "Check-in desk", checksIn: true)
    static let lunch = ScanContextRef(id: "c3", name: "Saturday lunch")

    static func pendingScan(_ i: Int, name: String?) -> PendingScan {
        PendingScan(clientScanId: "p\(i)", eventId: event.id, scanContextId: "c1", scanContextName: "Check-in desk",
                    input: ScanInput(participantId: "attend://checkin/x\(i)"), scannedAt: "2026-10-03T0\(i):1\(i):00Z", attempts: 1,
                    lastError: i == 1 ? "You're offline. Check your connection." : nil, knownName: name)
    }

    /// Sydney, 11:30 on Saturday 3 Oct (Fixtures.now) and a window on Sunday.
    static let sundayLunch = ScanContext(id: "c3", name: "Sunday lunch", position: 2,
                                         startsAt: "2026-10-04T12:00:00+10:00", endsAt: "2026-10-04T13:30:00+10:00")
    static let saturdayLunch = ScanContext(id: "c4", name: "Saturday lunch", position: 3,
                                           startsAt: "2026-10-03T11:00:00+10:00", endsAt: "2026-10-03T12:30:00+10:00")
}

// MARK: - Outcome → card

@Suite struct ScanCardMappingTests {
    let maya = Fixtures.participants[2]
    let ctx = Fixtures.contexts[0]
    let tz = Fixtures.event.timezone

    @Test func scannedAtCheckInCanBeUndone() {
        let result = ScanResult(outcome: "scanned", scan: Scan(id: "s1", participantEventId: maya.participantEventId, scannedAt: Fixtures.iso(0)),
                                scanContext: Fixtures.checkIn, participant: maya)
        let card = ScanOutcome.scanned(clientScanId: "s1", result: result, participant: maya)
            .card(key: "k", context: ctx, tz: tz, gateKey: "qr:x", input: ScanInput(participantId: "x"))
        #expect(card.kind == .scanned)
        #expect(card.title == "Scanned")
        #expect(card.message == "Checked in")
        #expect(card.contextName == "Check-in desk")
        #expect(card.contextId == "c1")
        #expect(card.canUndo)
        #expect(!card.retryable)
        #expect(card.participant == maya)
        #expect(card.gateKey == "qr:x")
        #expect(card.key == "k")
    }

    @Test func serverContextWinsAndNonCheckInHasNoMessage() {
        let result = ScanResult(outcome: "scanned", scanContext: Fixtures.lunch, participant: maya)
        let card = ScanOutcome.scanned(clientScanId: "s1", result: result, participant: maya)
            .card(key: "k", context: ctx, tz: tz, gateKey: nil, input: nil)
        #expect(card.message == nil)
        #expect(card.contextName == "Saturday lunch")
        #expect(card.contextId == "c3")
    }

    @Test func deduplicatedOrAnonymousScansCannotBeUndone() {
        let dedup = ScanResult(outcome: "scanned", deduplicated: true, scanContext: Fixtures.checkIn, participant: maya)
        #expect(!ScanOutcome.scanned(clientScanId: "s", result: dedup, participant: maya).card(key: "k", context: ctx, tz: tz, gateKey: nil, input: nil).canUndo)
        let anonymous = ScanResult(outcome: "scanned", scanContext: Fixtures.checkIn)
        #expect(!ScanOutcome.scanned(clientScanId: "s", result: anonymous, participant: nil).card(key: "k", context: ctx, tz: tz, gateKey: nil, input: nil).canUndo)
        let noContext = ScanResult(outcome: "scanned", participant: maya)
        #expect(!ScanOutcome.scanned(clientScanId: "s", result: noContext, participant: maya).card(key: "k", context: nil, tz: tz, gateKey: nil, input: nil).canUndo)
    }

    @Test func alreadyScannedSaysWhen() {
        let first = "2026-10-02T23:41:00Z"
        let result = ScanResult(outcome: "already_scanned", firstScanInContext: false, firstScannedAt: first, scanContext: Fixtures.checkIn, participant: maya)
        let card = ScanOutcome.alreadyScanned(clientScanId: "s", result: result, participant: maya)
            .card(key: "k", context: nil, tz: tz, gateKey: nil, input: nil)
        #expect(card.kind == .alreadyScanned)
        #expect(card.title == "Already scanned")
        #expect(card.message == "First scanned at \(ScanLogic.firstScanLabel(first, tz: tz))")
        #expect(card.contextId == "c1")
        #expect(!card.canUndo)
    }

    @Test func alreadyScannedWithoutTimeHasNoMessage() {
        let result = ScanResult(outcome: "already_scanned", firstScanInContext: false)
        let card = ScanOutcome.alreadyScanned(clientScanId: "s", result: result, participant: nil).card(key: "k", context: ctx, tz: tz, gateKey: nil, input: nil)
        #expect(card.message == nil)
        #expect(card.contextName == "Check-in desk")
    }

    @Test func queuedIsSavedOffline() {
        let pending = Fixtures.pendingScan(1, name: "Sam Lee")
        let card = ScanOutcome.queued(clientScanId: "p1", pending: pending, participant: nil, reason: "offline")
            .card(key: "k", context: ctx, tz: tz, gateKey: nil, input: pending.input)
        #expect(card.kind == .savedOffline)
        #expect(card.title == "Saved offline")
        #expect(card.message == "Offline, will confirm later")
        #expect(card.contextName == "Check-in desk")
        #expect(!card.retryable)
    }

    @Test func failuresAreRetryableUnlessNotRegistered() {
        let notFound = ScanOutcome.failed(clientScanId: "s", message: "Not registered for this event", participant: nil, notFound: true)
            .card(key: "k", context: ctx, tz: tz, gateKey: nil, input: nil)
        #expect(notFound.kind == .rejected)
        #expect(notFound.title == "Not registered")
        #expect(notFound.message == "No registration for this event matches that code.")
        #expect(!notFound.retryable)

        let expired = ScanOutcome.failed(clientScanId: "s", message: "Your session has expired. Please sign in again.", participant: maya, notFound: false)
            .card(key: "k", context: ctx, tz: tz, gateKey: nil, input: ScanInput(participantId: "x"))
        #expect(expired.title == "Couldn't scan")
        #expect(expired.message == "Your session has expired. Please sign in again.")
        #expect(expired.retryable)
        #expect(expired.participant == maya)
    }

    @Test func logLabelsAndNames() {
        let result = ScanResult(outcome: "scanned", participant: maya)
        #expect(ScanOutcome.scanned(clientScanId: "a", result: result, participant: maya).label == "Scanned")
        #expect(ScanOutcome.alreadyScanned(clientScanId: "a", result: result, participant: maya).label == "Already scanned")
        let queued = ScanOutcome.queued(clientScanId: "a", pending: Fixtures.pendingScan(2, name: "Arjun Patel"), participant: nil, reason: "")
        #expect(queued.label == "Saved offline")
        #expect(queued.displayName == "Arjun Patel")
        #expect(ScanOutcome.failed(clientScanId: "a", message: "x", participant: nil, notFound: true).label == "Not registered")
        #expect(ScanOutcome.failed(clientScanId: "a", message: "Boom", participant: nil, notFound: false).label == "Boom")
        #expect(ScanOutcome.failed(clientScanId: "a", message: "Boom", participant: nil, notFound: false).displayName == "Unknown attendee")
        #expect(ScanOutcome.scanned(clientScanId: "a", result: result, participant: maya).displayName == "Maya")
        #expect(Fixtures.pendingScan(3, name: nil).displayName == "Unknown attendee")
    }

    @Test func kindsMapToFeedbackAndTone() {
        #expect(ResultKind.checking.feedback == nil)
        #expect(ResultKind.undone.feedback == nil)
        #expect(ResultKind.scanned.feedback == .success)
        #expect(ResultKind.alreadyScanned.feedback == .warning)
        #expect(ResultKind.savedOffline.feedback == .info)
        #expect(ResultKind.rejected.feedback == .reject)
        #expect(ResultKind.scanned.tone == .success)
        #expect(ResultKind.alreadyScanned.tone == .warning)
        #expect(ResultKind.savedOffline.tone == .info)
        #expect(ResultKind.checking.tone == .info)
        #expect(ResultKind.rejected.tone == .danger)
        #expect(!ResultKind.checking.isFinal)
        #expect(ResultKind.allCases.filter(\.isFinal).count == 5)
    }

    @Test func localCardsForCodesThatNeverReachTheServer() {
        let code = ScanCard.notAttendCode(key: "k", gateKey: "qr:hi")
        #expect(code.kind == .rejected && code.gateKey == "qr:hi" && !code.retryable)
        let badge = ScanCard.notAttendBadge(key: "k", message: NdefParser.unlinkedBadge)
        #expect(badge.title == "Not an Attend badge" && badge.message == NdefParser.unlinkedBadge)
        let loading = ScanCard.stillLoading(key: "k", input: ScanInput(participantId: "x"))
        #expect(loading.retryable && loading.input?.participantId == "x")
    }

    @Test func accessibilityTextReadsTheWholeCard() {
        var card = ScanCard(key: "k", kind: .scanned, title: "Scanned", message: "Checked in", participant: maya, contextName: "Check-in desk")
        #expect(card.accessibilityText == "Scanned. Maya. Check-in desk. Checked in")
        card.participant = nil
        card.message = nil
        #expect(card.accessibilityText == "Scanned. Check-in desk")
    }
}

// MARK: - Labels

@Suite struct ScanLabelTests {
    let tz = "Australia/Sydney"

    @Test func firstScanLabelIsTimeTodayElseWeekdayAndTime() {
        let today = "2026-10-02T23:41:00Z" // 9:41 on Sat 3 Oct in Sydney
        let time = Time.time(today, tz: tz)!
        #expect(ScanLogic.firstScanLabel(today, tz: tz, now: Fixtures.now) == time)
        let yesterday = "2026-10-02T03:00:00Z" // Fri 2 Oct, 1 PM in Sydney
        #expect(ScanLogic.firstScanLabel(yesterday, tz: tz, now: Fixtures.now) == "\(Time.weekday(yesterday, tz: tz)!) \(Time.time(yesterday, tz: tz)!)")
        #expect(ScanLogic.firstScanLabel("garbage", tz: tz, now: Fixtures.now) == "garbage")
    }

    @Test func windowLabels() {
        let s = Fixtures.saturdayLunch
        #expect(s.windowLabel(tz: tz, now: Fixtures.now) == "\(Time.time(s.startsAt, tz: tz)!) – \(Time.time(s.endsAt, tz: tz)!)")
        let sun = Fixtures.sundayLunch
        #expect(sun.windowLabel(tz: tz, now: Fixtures.now) == "\(Time.weekday(sun.startsAt, tz: tz)!) \(Time.time(sun.startsAt, tz: tz)!) – \(Time.time(sun.endsAt, tz: tz)!)")
        var open = s
        open.endsAt = nil
        #expect(open.windowLabel(tz: tz, now: Fixtures.now) == "from \(Time.time(s.startsAt, tz: tz)!)")
        #expect(Fixtures.contexts[0].windowLabel(tz: tz, now: Fixtures.now) == nil)
    }

    @Test func liveWindowsSayNow() {
        #expect(Fixtures.saturdayLunch.isLive(now: Fixtures.now))
        #expect(Fixtures.saturdayLunch.whenLabel(tz: tz, now: Fixtures.now) == "Now")
        #expect(!Fixtures.sundayLunch.isLive(now: Fixtures.now))
        #expect(Fixtures.sundayLunch.whenLabel(tz: tz, now: Fixtures.now) == Fixtures.sundayLunch.windowLabel(tz: tz, now: Fixtures.now))
        #expect(Fixtures.contexts[0].whenLabel(tz: tz, now: Fixtures.now) == nil)
    }

    @Test func contextIconsAndPurpose() {
        #expect(Fixtures.contexts[0].systemImage == "person.crop.circle.badge.checkmark")
        #expect(Fixtures.contexts[1].systemImage == "airplane.arrival")
        #expect(Fixtures.contexts[2].systemImage == "mappin.and.ellipse")
        #expect(Fixtures.contexts[0].purpose == "Checks people in")
        #expect(Fixtures.contexts[1].purpose == "Airport pickup")
        #expect(Fixtures.contexts[2].purpose == nil)
    }

    @Test func statusChip() {
        #expect(ScanLogic.status(inFlight: 1, ready: false, camera: .denied).label == "Checking…")
        #expect(ScanLogic.status(inFlight: 0, ready: false, camera: .granted).label == "Loading…")
        #expect(ScanLogic.status(inFlight: 0, ready: true, camera: .unavailable).label == "Camera off")
        let ready = ScanLogic.status(inFlight: 0, ready: true, camera: .granted)
        #expect(ready.label == "Ready to scan" && ready.tone == .success)
    }

    @Test func personStatusInFindSheet() {
        let ps = Fixtures.participants
        // 0: not checked in (i % 3 == 0), 1: checked in at c1, 10: withdrawn, 11: awaiting guardian but not checked in
        #expect(ScanLogic.personStatus(ps[0], selectedContextId: "c1", tz: tz).text == "Not checked in")
        let here = ScanLogic.personStatus(ps[1], selectedContextId: "c1", tz: tz, now: Fixtures.now)
        #expect(here.text == "Scanned here \(ScanLogic.firstScanLabel(ps[1].scansByContext[0].firstScannedAt!, tz: tz, now: Fixtures.now))")
        #expect(here.tone == .success)
        #expect(ScanLogic.personStatus(ps[1], selectedContextId: "c3", tz: tz).text == "Checked in")
        let withdrawn = ScanLogic.personStatus(ps[10], selectedContextId: "c1", tz: tz)
        #expect(withdrawn.text == "Withdrawn" && withdrawn.tone == .danger)
        #expect(ScanLogic.actionLabel(checksIn: true) == "Check In")
        #expect(ScanLogic.actionLabel(checksIn: false) == "Scan")
    }

    @Test func headlinePrefersFullNameWhenItExtendsTheDisplayName() {
        let maya = Fixtures.participants[2]
        #expect(ScanLogic.headline(maya) == ("Maya Chen", nil))
        var nick = maya
        nick.displayName = "Mimi"
        #expect(ScanLogic.headline(nick) == ("Mimi", "Maya Chen"))
        nick.fullName = nil
        #expect(ScanLogic.headline(nick) == ("Mimi", nil))
    }
}

// MARK: - Kiosk

@Suite struct KioskLogicTests {
    let maya = Fixtures.participants[2]

    func card(_ kind: ResultKind, title: String = "", participant: Participant? = nil) -> ScanCard {
        ScanCard(key: "k", kind: kind, title: title, participant: participant)
    }

    @Test func friendlyFirstNameMessages() {
        #expect(KioskLogic.message(for: card(.checking, participant: maya)).title == "Hi Maya!")
        #expect(KioskLogic.message(for: card(.checking)).title == "One moment…")
        #expect(KioskLogic.message(for: card(.scanned, participant: maya)) == .init(title: "Welcome, Maya!", body: "You're all set. Enjoy the event!"))
        #expect(KioskLogic.message(for: card(.savedOffline)).title == "Welcome!")
        #expect(KioskLogic.message(for: card(.alreadyScanned, participant: maya)).title == "You're already in, Maya")
        #expect(KioskLogic.message(for: card(.alreadyScanned)).title == "Already scanned")
        #expect(KioskLogic.message(for: card(.undone)).title == "Ready")
    }

    @Test func rejectionsNeverLeakDetails() {
        #expect(KioskLogic.message(for: card(.rejected, title: "Still loading")).title == "Just a moment")
        #expect(KioskLogic.message(for: card(.rejected, title: "Not an Attend code")).title == "That's not an Attend ticket")
        #expect(KioskLogic.message(for: card(.rejected, title: "Not an Attend badge")).title == "That's not an Attend ticket")
        let other = KioskLogic.message(for: card(.rejected, title: "Couldn't scan", participant: maya))
        #expect(other == .init(title: "We couldn't find your ticket", body: "Please see a staff member and they'll sort it out."))
    }

    @Test func firstNameOnly() {
        var p = maya
        p.displayName = nil
        #expect(KioskLogic.firstName(p) == "Maya")
        p.fullName = "  "
        #expect(KioskLogic.firstName(p) == "there")
        p.displayName = "Kai Tanaka-Smith"
        #expect(KioskLogic.firstName(p) == "Kai")
    }

    @Test func pinHashIsSaltedAndStable() {
        let h = KioskLogic.hashPin("1234")
        #expect(h == KioskLogic.hashPin("1234"))
        #expect(h != KioskLogic.hashPin("1235"))
        #expect(h.count == 64)
        #expect(!h.contains("1234"))
    }

    @Test func pinInputIsDigitsOnlyAndUncapped() {
        #expect(KioskLogic.sanitizePin("12a3-45") == "12345")
        #expect(KioskLogic.sanitizePin("1234567890123") == "1234567890123")
        #expect(KioskLogic.sanitizePin("٣٤") == "")
        #expect(KioskLogic.sanitizePin("") == "")
    }

    @Test func setupNeedsTwoMatchingEntries() {
        var setup = KioskPinSetup()
        #expect(setup.prompt == "Choose an exit PIN")
        #expect(setup.submit("") == nil)
        #expect(!setup.confirming)
        #expect(setup.submit("123456") == nil)
        #expect(setup.confirming)
        #expect(setup.prompt == "Enter the PIN again to confirm")
        // A prefix of the first entry isn't a match.
        #expect(setup.submit("12345") == nil)
        #expect(!setup.confirming)
        #expect(setup.prompt == "PINs don't match. Try again.")
        #expect(setup.submit("24") == nil)
        #expect(setup.error == nil)
        #expect(setup.submit("24") == KioskLogic.hashPin("24"))
    }

    @Test func setupCanStartOver() {
        var setup = KioskPinSetup()
        _ = setup.submit("1111")
        setup.restart()
        #expect(!setup.confirming && setup.error == nil)
    }

    @Test func lockUnlocksWithTheRightPin() {
        var lock = KioskPinLock(hash: KioskLogic.hashPin("1234"))
        #expect(lock.attempt("1234", now: Fixtures.now) == .unlocked)
        #expect(lock.prompt(after: nil, now: Fixtures.now) == "Staff: enter the PIN.")
    }

    @Test func longPinsWork() {
        var lock = KioskPinLock(hash: KioskLogic.hashPin("314159265358"))
        #expect(lock.attempt("31415926535", now: Fixtures.now) == .wrong(triesLeft: 2))
        #expect(lock.attempt("314159265358", now: Fixtures.now) == .unlocked)
    }

    @Test func threeWrongTriesLockForThirtySeconds() {
        var lock = KioskPinLock(hash: KioskLogic.hashPin("1234"))
        let t0 = Fixtures.now
        let first = lock.attempt("0000", now: t0)
        #expect(first == .wrong(triesLeft: 2))
        #expect(lock.prompt(after: first, now: t0) == "Wrong PIN. 2 tries left.")
        let second = lock.attempt("1111", now: t0)
        #expect(lock.prompt(after: second, now: t0) == "Wrong PIN. 1 try left.")
        #expect(lock.attempt("2222", now: t0) == .lockedOut)
        #expect(lock.isLocked(now: t0.addingTimeInterval(29)))
        #expect(lock.secondsLeft(now: t0.addingTimeInterval(0.5)) == 30)
        #expect(lock.prompt(after: .lockedOut, now: t0.addingTimeInterval(20)) == "Too many wrong tries. Try again in 10 s.")
        // Even the right PIN is refused while locked.
        #expect(lock.attempt("1234", now: t0.addingTimeInterval(10)) == .lockedOut)
        // Afterwards the counter starts again.
        let later = t0.addingTimeInterval(31)
        #expect(!lock.isLocked(now: later))
        #expect(lock.attempt("9999", now: later) == .wrong(triesLeft: 2))
        #expect(lock.attempt("1234", now: later) == .unlocked)
        #expect(lock.wrongTries == 0)
    }
}

// MARK: - Scan model (against the in-process demo backend)

@MainActor
@Suite(.serialized) struct ScanModelTests {
    let app: AppModel
    let model: ScanModel
    var played: [FeedbackKind] { feedback.kinds }
    let feedback = FeedbackRecorder()

    init() async throws {
        DemoBackend.shared.isOffline = false
        app = AppModel(demo: true)
        _ = try await app.events.refresh()
        app.events.select(DemoData.mainEventId)
        model = ScanModel(app: app)
        let recorder = feedback
        model.playFeedback = { recorder.kinds.append($0) }
        model.actionFeedback = { _ in }
        await model.start()
    }

    /// Demo roster people who haven't been scanned at the check-in desk yet (tests use different ones).
    func unscanned(_ n: Int) -> Participant {
        let roster = app.participants.roster(DemoData.mainEventId)?.participants ?? []
        return roster.filter { $0.isActive && !$0.scansByContext.contains { $0.scanContextId == "c1" } }[n]
    }

    func settle() async {
        for _ in 0..<60 where model.card?.kind.isFinal == false || model.inFlight > 0 || model.syncing {
            try? await Task.sleep(for: .milliseconds(50))
        }
    }

    @Test func loadsCheckpointsAndDefaultsToTheLiveOne() {
        #expect(model.ready)
        #expect(model.contexts?.map(\.id) == ["c1", "c2", "c3", "c4"])
        // The demo's lunch window is open now.
        #expect(model.selectedContextId == "c3")
        model.selectContext("c1")
        #expect(model.selectedContextId == "c1")
        #expect(app.settings.selectedContexts[DemoData.mainEventId] == "c1")
    }

    @Test func scanShowsConfirmingThenScannedAndGatesRepeats() async {
        model.selectContext("c1")
        let p = unscanned(0)
        let code = "attend://checkin/\(p.participantId)"
        model.onCameraCodes([code])
        // The roster knows them and sees no problem: a muted "Confirming…", not a success yet.
        #expect(model.card?.kind == .confirming)
        #expect(model.card?.title == "Confirming…")
        #expect(played.isEmpty)
        #expect(model.card?.participant?.participantEventId == p.participantEventId)
        let key = model.card?.key
        // The same code in the next frames is ignored.
        model.onCameraCodes([code, code])
        #expect(model.card?.key == key)
        await settle()
        #expect(model.card?.kind == .scanned)
        #expect(model.card?.message == "Checked in")
        #expect(model.card?.canUndo == true)
        #expect(played == [.success])
        #expect(model.inFlight == 0)
        #expect(app.participants.roster(DemoData.mainEventId)?.byEventId[p.participantEventId]?.isCheckedIn == true)

        // Dismissing releases the gate: showing it again is "already scanned".
        model.dismiss()
        #expect(model.card == nil)
        model.onCameraCodes([code])
        await settle()
        #expect(model.card?.kind == .alreadyScanned)
        #expect(played == [.success, .warning])
    }

    @Test func undoMarksTheCardAndTheRoster() async throws {
        model.selectContext("c1")
        let p = unscanned(1)
        model.onCameraCodes(["attend://checkin/\(p.participantId)"])
        await settle()
        let card = try #require(model.card)
        await model.undo(card)
        #expect(model.card?.kind == .undone)
        #expect(model.card?.title == "Scan undone")
        #expect(model.card?.canUndo == false)
        #expect(model.card?.busy == false)
        #expect(app.participants.roster(DemoData.mainEventId)?.byEventId[p.participantEventId]?.isCheckedIn == false)
    }

    @Test func serverRejectionInterruptsWithABanner() async throws {
        model.selectContext("c1")
        let roster = app.participants.roster(DemoData.mainEventId)?.participants ?? []
        let p = try #require(roster.first { $0.status == "withdrawn" && !$0.scansByContext.contains { $0.scanContextId == "c1" } })
        model.onCameraCodes(["attend://checkin/\(p.participantId)"])
        // The cache already has doubts, so no muted tick.
        #expect(model.card?.kind == .checking)
        await settle()
        #expect(model.card?.kind == .rejected)
        #expect(model.card?.title == "Withdrawn")
        #expect(played == [.reject])
        let alert = try #require(model.alerts.first)
        #expect(alert.headline == "\(p.name): registration withdrawn")
        #expect(alert.participantEventId == p.participantEventId)
        // The scan Attend recorded was taken back.
        #expect(app.participants.roster(DemoData.mainEventId)?.byEventId[p.participantEventId]?.scansByContext.contains { $0.scanContextId == "c1" } == false)
        // The banner outlives the card until it's dismissed.
        model.dismiss()
        #expect(model.alerts.count == 1)
        model.dismissAlert(alert.clientScanId)
        #expect(model.alerts.isEmpty)
    }

    @Test func foreignCodesAreRejectedLocally() {
        model.onCameraCodes(["https://example.com/menu"])
        #expect(model.card?.title == "Not an Attend code")
        #expect(played == [.reject])
        model.onNfc(.unrecognised(NdefParser.noData))
        #expect(model.card?.title == "Not an Attend badge")
    }

    @Test func unknownTicketIsNotRegistered() async {
        model.onCameraCodes(["attend://checkin/\(UUID().uuidString.lowercased())"])
        await settle()
        #expect(model.card?.kind == .rejected)
        #expect(model.card?.title == "Not registered")
        #expect(played == [.reject])
    }

    @Test func nfcBadgesScan() async throws {
        model.selectContext("c1")
        let roster = app.participants.roster(DemoData.mainEventId)?.participants ?? []
        let p = try #require(roster.first { $0.nfcBadgeToken != nil && !$0.scansByContext.contains { $0.scanContextId == "c1" } && $0.isActive })
        model.onNfc(.input(ScanInput(badgeToken: p.nfcBadgeToken, source: "nfc")))
        await settle()
        #expect(model.card?.kind == .scanned)
        #expect(model.card?.participant?.participantEventId == p.participantEventId)
    }

    @Test func offlineScansQueueThenSync() async {
        model.selectContext("c1")
        let p = unscanned(2)
        DemoBackend.shared.isOffline = true
        defer { DemoBackend.shared.isOffline = false }
        model.onCameraCodes(["attend://checkin/\(p.participantId)"])
        await settle()
        #expect(model.card?.kind == .savedOffline)
        #expect(model.card?.participant?.participantEventId == p.participantEventId)
        #expect(model.pendingCount == 1)
        #expect(played == [.info])

        DemoBackend.shared.isOffline = false
        model.syncNow()
        await settle()
        #expect(model.pendingCount == 0)
    }

    @Test func manualCheckInBypassesTheGate() async {
        model.selectContext("c1")
        let p = unscanned(3)
        model.checkInManually(participantEventId: p.participantEventId)
        await settle()
        #expect(model.card?.kind == .scanned)
        model.checkInManually(participantEventId: p.participantEventId)
        await settle()
        #expect(model.card?.kind == .alreadyScanned)
    }

    @Test func kioskHideKeepsTheGateClosed() async throws {
        let kiosk = ScanModel(app: app, fixedEventId: DemoData.mainEventId, lockedContextId: "c1")
        kiosk.playFeedback = { _ in }
        await kiosk.start()
        #expect(kiosk.selectedContextId == "c1")
        kiosk.selectContext("c2")
        #expect(kiosk.selectedContextId == "c1")
        let code = "attend://checkin/\(unscanned(4).participantId)"
        kiosk.onCameraCodes([code])
        await Self.settle(kiosk)
        let key = try #require(kiosk.card?.key)
        kiosk.hide(key)
        #expect(kiosk.card == nil)
        kiosk.onCameraCodes([code])
        #expect(kiosk.card == nil)
    }

    static func settle(_ m: ScanModel) async {
        for _ in 0..<60 where m.card?.kind.isFinal == false || m.inFlight > 0 {
            try? await Task.sleep(for: .milliseconds(50))
        }
    }

    @Test func findPersonSearchesLocallyThenRemotely() async {
        model.setQuery("maya")
        #expect(!model.search.results.isEmpty)
        #expect(model.search.results.allSatisfy { $0.name.lowercased().hasPrefix("maya") || ($0.fullName ?? "").lowercased().contains("maya") })
        #expect(model.search.remoteLoading)
        #expect(model.search.directInput == nil)
        for _ in 0..<40 where model.search.remoteLoading { try? await Task.sleep(for: .milliseconds(50)) }
        #expect(!model.search.remoteLoading)
        #expect(model.search.remoteError == nil)

        let id = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
        model.setQuery(id)
        #expect(model.search.directInput == ScanInput(participantId: id, source: "manual"))

        model.setQuery("m")
        #expect(!model.search.remoteLoading)
    }
}

/// Collects scan feedback in tests instead of playing it.
@MainActor
final class FeedbackRecorder {
    var kinds: [FeedbackKind] = []
}
