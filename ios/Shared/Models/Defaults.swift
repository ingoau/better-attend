import Foundation

/// Supplies the value used when a JSON key is missing or `null`.
protocol DefaultProvider {
    associatedtype Value: Codable & Hashable & Sendable
    static var value: Value { get }
}

/// Decodes a missing or `null` key as `P.value` instead of failing, like kotlinx's
/// `explicitNulls = false` + `coerceInputValues = true` on the Android side. Declare the
/// same default in Swift so memberwise initializers get it too:
/// `@Default<False> var checksIn: Bool = false`.
@propertyWrapper
struct Default<P: DefaultProvider>: Codable, Hashable, Sendable {
    var wrappedValue: P.Value

    init(wrappedValue: P.Value) { self.wrappedValue = wrappedValue }

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        wrappedValue = c.decodeNil() ? P.value : try c.decode(P.Value.self)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(wrappedValue)
    }
}

extension KeyedDecodingContainer {
    func decode<P>(_ type: Default<P>.Type, forKey key: Key) throws -> Default<P> {
        try decodeIfPresent(type, forKey: key) ?? Default(wrappedValue: P.value)
    }
}

enum False: DefaultProvider { static var value: Bool { false } }
enum True: DefaultProvider { static var value: Bool { true } }
enum Zero: DefaultProvider { static var value: Int { 0 } }
enum Empty<Element: Codable & Hashable & Sendable>: DefaultProvider { static var value: [Element] { [] } }
