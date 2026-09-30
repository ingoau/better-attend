import Foundation

/// A platform-free NDEF record, so parsing and building can be unit tested without CoreNFC.
struct RawNdefRecord: Hashable, Sendable {
    var tnf: UInt8
    var type: Data
    var payload: Data
}

enum NfcParseResult: Hashable, Sendable {
    case input(ScanInput)
    /// The tag was read but holds nothing we can scan. The message is shown on the result card.
    case unrecognised(String)
}

/// The Hack Club badge NDEF format, kept compatible with badges written by the old app:
///  1. (optional) URI record `https://badge.hackclub.com/t/<slack user id>` so any phone tapping the
///     badge opens the person's Hack Club badge page;
///  2. External record (TNF 0x04) of type `hackclub.com:attend` whose payload is the ASCII badge token.
enum NfcBadgeFormat {
    static let externalDomain = "hackclub.com"
    static let externalTypeName = "attend"
    static let externalType = "\(externalDomain):\(externalTypeName)"
    static let badgeURLPrefix = "https://badge.hackclub.com/t/"

    static let tnfWellKnown: UInt8 = 0x01
    static let tnfAbsoluteURI: UInt8 = 0x03
    static let tnfExternal: UInt8 = 0x04

    /// The records to write to a blank badge.
    static func badgeRecords(slackUserId: String?, token: String) -> [RawNdefRecord] {
        var records: [RawNdefRecord] = []
        if let slack = slackUserId?.trimmingCharacters(in: .whitespaces), !slack.isEmpty {
            records.append(uriRecord(badgeURLPrefix + slack))
        }
        records.append(RawNdefRecord(
            tnf: tnfExternal,
            type: Data(externalType.utf8),
            payload: Data(token.trimmingCharacters(in: .whitespaces).utf8)
        ))
        return records
    }

    /// The Attend token on a tag, or nil if there isn't one (used to verify a write).
    static func readToken(_ records: [RawNdefRecord]) -> String? {
        records.first {
            $0.tnf == tnfExternal && String(decoding: $0.type, as: UTF8.self).caseInsensitiveCompare(externalType) == .orderedSame
        }.map { String(decoding: $0.payload, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines) }?.nonBlank
    }

    /// A well-known URI record, using the NFC Forum prefix abbreviation when one applies.
    static func uriRecord(_ uri: String) -> RawNdefRecord {
        // Longest matching prefix wins (e.g. "https://www." over "https://").
        let (code, prefix) = NdefParser.uriPrefixes.enumerated()
            .filter { $0.offset > 0 && uri.hasPrefix($0.element) }
            .max { $0.element.count < $1.element.count }
            .map { (UInt8($0.offset), $0.element) } ?? (0, "")
        var payload = Data([code])
        payload.append(Data(uri.dropFirst(prefix.count).utf8))
        return RawNdefRecord(tnf: tnfWellKnown, type: Data("U".utf8), payload: payload)
    }
}

enum NdefParser {
    static let noData = "This tag doesn't have any Attend data on it."
    static let unlinkedBadge = "This badge isn't linked to Attend yet. Find the person manually, then rewrite their badge."
    static let unrecognised = "This tag isn't an Attend badge or ticket."

    /// NFC Forum URI Record Type Definition abbreviation table (index = prefix byte).
    static let uriPrefixes = [
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:",
        "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://",
        "tcpobex://", "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    ]

    /// Turns the records on a tag into something to scan:
    ///  1. an external `hackclub.com:attend` record → badge token;
    ///  2. else URI / Text records → `ScanCode.parse` (e.g. `attend://checkin/<id>`);
    ///  3. else the first record's payload as text → `ScanCode.parse`.
    static func parse(_ records: [RawNdefRecord]) -> NfcParseResult {
        guard let first = records.first else { return .unrecognised(noData) }

        if let rec = records.first(where: {
            $0.tnf == NfcBadgeFormat.tnfExternal
                && String(decoding: $0.type, as: UTF8.self).caseInsensitiveCompare(NfcBadgeFormat.externalType) == .orderedSame
        }) {
            let token = String(decoding: rec.payload, as: UTF8.self)
                .trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(.controlCharacters))
            if !token.isEmpty { return .input(ScanInput(badgeToken: token, source: "nfc")) }
        }

        var sawBadgeURL = false
        for rec in records {
            guard let text = textOf(rec) else { continue }
            if let input = ScanCode.parse(text, source: "nfc") { return .input(input) }
            if text.lowercased().hasPrefix(NfcBadgeFormat.badgeURLPrefix) { sawBadgeURL = true }
        }
        if sawBadgeURL { return .unrecognised(unlinkedBadge) }

        let fallback = first.payload.isEmpty ? nil : String(decoding: first.payload, as: UTF8.self)
        if let fallback, let input = ScanCode.parse(fallback, source: "nfc") { return .input(input) }
        return .unrecognised(fallback?.isBlank ?? true ? noData : unrecognised)
    }

    /// Decodes a URI or Text record to a string, or nil for any other record type.
    static func textOf(_ rec: RawNdefRecord) -> String? {
        if rec.tnf == NfcBadgeFormat.tnfWellKnown && rec.type == Data("U".utf8) { return decodeURI(rec.payload) }
        if rec.tnf == NfcBadgeFormat.tnfWellKnown && rec.type == Data("T".utf8) { return decodeText(rec.payload) }
        if rec.tnf == NfcBadgeFormat.tnfAbsoluteURI { return String(decoding: rec.type, as: UTF8.self) }
        return nil
    }

    static func decodeURI(_ payload: Data) -> String? {
        guard let code = payload.first else { return nil }
        let prefix = Int(code) < uriPrefixes.count ? uriPrefixes[Int(code)] : ""
        return prefix + String(decoding: payload.dropFirst(), as: UTF8.self)
    }

    static func decodeText(_ payload: Data) -> String? {
        guard let status = payload.first else { return nil }
        let utf16 = status & 0x80 != 0
        let langLength = Int(status & 0x3F)
        let start = payload.startIndex + 1 + langLength
        guard start <= payload.endIndex else { return nil }
        let body = payload[start...]
        return utf16 ? String(data: body, encoding: .utf16) : String(decoding: body, as: UTF8.self)
    }
}
