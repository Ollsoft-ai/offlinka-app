import Foundation

/// An inclusive byte range of a resource, as in `Content-Range: bytes start-end/total`.
struct ByteRange: Equatable, Sendable {
    let start: Int64
    let end: Int64

    var length: Int64 { end - start + 1 }

    enum Parsed: Equatable, Sendable {
        /// No usable `Range` header: answer with the whole resource.
        case none
        case satisfiable(ByteRange)
        /// Answer `416` with `Content-Range: bytes */total`.
        case unsatisfiable
    }

    /// Parses a `Range` request header (RFC 9110 §14.1.2). Only the first range of a multi-range
    /// request is served. `maxLength` caps the answer; clients (media elements) ask again for the rest.
    static func parse(_ header: String?, totalLength: Int64, maxLength: Int64) -> Parsed {
        guard let header = header?.trimmingCharacters(in: .whitespaces), !header.isEmpty else { return .none }
        let lower = header.lowercased()
        guard lower.hasPrefix("bytes=") else { return .none }
        let spec = lower.dropFirst("bytes=".count)
            .split(separator: ",", omittingEmptySubsequences: true)
            .first?
            .trimmingCharacters(in: .whitespaces) ?? ""
        guard let dash = spec.firstIndex(of: "-") else { return .none }
        let first = spec[..<dash].trimmingCharacters(in: .whitespaces)
        let last = spec[spec.index(after: dash)...].trimmingCharacters(in: .whitespaces)

        let start: Int64
        var end: Int64
        if first.isEmpty {
            // Suffix range: the last N bytes.
            guard let suffix = Int64(last) else { return .none }
            guard suffix > 0, totalLength > 0 else { return .unsatisfiable }
            start = max(0, totalLength - suffix)
            end = totalLength - 1
        } else {
            guard let parsedStart = Int64(first), parsedStart >= 0 else { return .none }
            start = parsedStart
            if last.isEmpty {
                end = totalLength - 1
            } else {
                guard let parsedEnd = Int64(last), parsedEnd >= start else { return .none }
                end = min(parsedEnd, totalLength - 1)
            }
            guard start < totalLength else { return .unsatisfiable }
        }
        if maxLength > 0 {
            end = min(end, start + maxLength - 1)
        }
        return .satisfiable(ByteRange(start: start, end: end))
    }
}
