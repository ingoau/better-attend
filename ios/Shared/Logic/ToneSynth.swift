import Foundation

/// The four kinds of scan feedback; each has its own sound and haptic pattern.
enum FeedbackKind: String, CaseIterable, Sendable {
    case success, warning, info, reject
}

/// Synthesises the scanner's short feedback cues at runtime (no bundled audio assets): soft sine
/// notes with a little harmonic body and click-free attack/release envelopes, as 16-bit mono WAV.
enum ToneSynth {
    static let sampleRate = 44_100

    /// One note: `freq` Hz for `ms` milliseconds, then `gapMs` of silence.
    struct Note: Sendable {
        var freq: Double
        var ms: Int
        var gapMs: Int = 0
        var volume: Double = 0.8
        var harmonic: Double = 0.18
    }

    static func notes(for kind: FeedbackKind) -> [Note] {
        switch kind {
        // Bright rising major third: "yes!"
        case .success: [Note(freq: 1046.5, ms: 90, gapMs: 10), Note(freq: 1318.5, ms: 170)]
        // Neutral repeated note: "seen this before"
        case .warning: [Note(freq: 880, ms: 80, gapMs: 60), Note(freq: 880, ms: 80)]
        // Gentle single low-mid blip, quieter: "saved, nothing to worry about"
        case .info: [Note(freq: 659.3, ms: 70, gapMs: 20, volume: 0.55), Note(freq: 784, ms: 110, volume: 0.55)]
        // Low descending buzz with more harmonics: "no"
        case .reject: [Note(freq: 311.1, ms: 120, gapMs: 20, harmonic: 0.45), Note(freq: 233.1, ms: 170, harmonic: 0.45)]
        }
    }

    /// Renders notes to signed 16-bit PCM samples.
    static func render(_ notes: [Note], sampleRate: Int = sampleRate) -> [Int16] {
        let total = notes.reduce(0) { $0 + ($1.ms + $1.gapMs) * sampleRate / 1000 }
        var out = [Int16](repeating: 0, count: total)
        var offset = 0
        for n in notes {
            let len = n.ms * sampleRate / 1000
            let attack = max(min(len / 4, sampleRate * 6 / 1000), 1)
            let release = max(min(len / 2, sampleRate * 45 / 1000), 1)
            for i in 0..<len {
                let t = Double(i) / Double(sampleRate)
                let env: Double = i < attack ? Double(i) / Double(attack)
                    : i > len - release ? Double(len - i) / Double(release) : 1
                let s = sin(2 * .pi * n.freq * t) + n.harmonic * sin(4 * .pi * n.freq * t)
                let v = s / (1 + n.harmonic) * env * n.volume
                out[offset + i] = Int16(clamping: Int((v * Double(Int16.max)).rounded()))
            }
            offset += len + n.gapMs * sampleRate / 1000
        }
        return out
    }

    /// Wraps PCM samples in a minimal RIFF/WAVE container.
    static func wav(_ samples: [Int16], sampleRate: Int = sampleRate) -> Data {
        var d = Data(capacity: 44 + samples.count * 2)
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        let dataBytes = UInt32(samples.count * 2)
        d.append(contentsOf: Array("RIFF".utf8)); u32(36 + dataBytes); d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8)); u32(16); u16(1); u16(1)
        u32(UInt32(sampleRate)); u32(UInt32(sampleRate * 2)); u16(2); u16(16)
        d.append(contentsOf: Array("data".utf8)); u32(dataBytes)
        for s in samples { u16(UInt16(bitPattern: s)) }
        return d
    }

    static func wav(for kind: FeedbackKind) -> Data { wav(render(notes(for: kind))) }
}
