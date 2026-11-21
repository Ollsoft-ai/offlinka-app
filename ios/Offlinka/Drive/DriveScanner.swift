import Foundation

/// The parts of a ZIM file name such as `wikipedia_cs_all_maxi-offlinka_2026-09.zim`.
///
/// Used to pick one edition per book before opening anything: the same book can be on the drive
/// twice (`Knihovna/` maxi and `Telefon/` mini), and only the better one is shown.
struct ZimFileName: Hashable, Sendable {
    enum Flavour: String, Sendable {
        case maxi, novid, nopic, mini, none
    }

    let fileName: String
    /// File name without `.zim`.
    let stem: String
    /// Name without flavour and date, lowercased: `wikipedia_cs_all`, `maps_en_czech-republic`.
    let baseKey: String
    let flavour: Flavour
    /// `2026-09` (or `2026-09-29`) when the name ends with a date.
    let date: String?

    init(fileName: String) {
        self.fileName = fileName
        var stem = fileName
        if stem.lowercased().hasSuffix(".zim") {
            stem = String(stem.dropLast(4))
        }
        self.stem = stem

        var rest = stem
        var date: String?
        if let match = rest.range(of: #"_[0-9]{4}-[0-9]{2}(-[0-9]{2})?$"#, options: .regularExpression) {
            date = String(rest[match].dropFirst())
            rest = String(rest[..<match.lowerBound])
        }
        self.date = date

        var flavour = Flavour.none
        if let underscore = rest.lastIndex(of: "_") {
            let lastPart = rest[rest.index(after: underscore)...]
            // `maxi-offlinka` is our re-pack of the maxi flavour.
            let head = lastPart.split(separator: "-", maxSplits: 1).first.map { $0.lowercased() } ?? ""
            if let parsed = Flavour(rawValue: head), parsed != .none {
                flavour = parsed
                rest = String(rest[..<underscore])
            }
        }
        self.flavour = flavour
        self.baseKey = rest.lowercased()
    }

    /// Higher is better: full editions beat text-only ones, which beat the phone minis.
    var flavourRank: Int {
        switch flavour {
        case .maxi: return 4
        case .novid: return 3
        case .nopic, .none: return 2
        case .mini: return 1
        }
    }
}

/// One ZIM file found on the drive.
struct DriveEntry: Hashable, Sendable {
    let url: URL
    let folder: DriveFolder
    let name: ZimFileName
    /// Our search index (`.offidx`) next to the ZIM, if the drive build shipped one.
    let indexURL: URL?
    let sizeBytes: Int64

    /// Sort key (higher is better): `Knihovna/` before `Telefon/`, then flavour, then newer date.
    var rank: (Int, Int, String, String) {
        (folder == .library ? 1 : 0, name.flavourRank, name.date ?? "", name.fileName)
    }
}

/// What a scan of the picked folder found.
struct DriveScan: Sendable {
    /// The folder the user picked (normally the drive root `OFFLINKA`).
    let root: URL
    /// One entry per book, the best edition, sorted by `baseKey`.
    let books: [DriveEntry]
    /// Editions that lost to a better one of the same book (kept for diagnostics and tests).
    let shadowed: [DriveEntry]
}

enum DriveScanProblem: Error, Equatable, Sendable {
    /// The folder cannot be read (unplugged drive, revoked access).
    case notReachable
    /// No `*.zim` found. `sawDriveFolders` tells "wrong folder picked" from "folders are there but empty".
    case noBooks(sawDriveFolders: Bool)
}

/// Finds the books on the drive: `Knihovna/*.zim` and `Telefon/*.zim`, plus `.offidx` indexes.
/// Read-only: never creates, moves or deletes anything.
struct DriveScanner {
    var fileManager: FileManager = .default

    func scan(root: URL) throws -> DriveScan {
        guard isDirectory(root) else { throw DriveScanProblem.notReachable }

        var found: [DriveEntry] = []
        var sawDriveFolders = false
        let children = listing(of: root)
        for folder in DriveFolder.onDrive {
            let match = children.first { child in
                Self.sameName(child.lastPathComponent, folder.rawValue) && isDirectory(child)
            }
            if let dir = match {
                sawDriveFolders = true
                found += zims(in: dir, folder: folder)
            }
        }

        if !sawDriveFolders {
            // The user picked `Knihovna` or `Telefon` itself, or some folder that just holds ZIMs.
            let pickedFolder = DriveFolder.onDrive.first { Self.sameName(root.lastPathComponent, $0.rawValue) }
            found += zims(in: root, folder: pickedFolder ?? .library)
        }

        guard !found.isEmpty else { throw DriveScanProblem.noBooks(sawDriveFolders: sawDriveFolders) }
        return Self.choose(found, root: root)
    }

    /// Keeps the best edition of every book.
    static func choose(_ entries: [DriveEntry], root: URL) -> DriveScan {
        var groups: [String: [DriveEntry]] = [:]
        for entry in entries {
            groups[entry.name.baseKey, default: []].append(entry)
        }
        var winners: [DriveEntry] = []
        var shadowed: [DriveEntry] = []
        for key in groups.keys.sorted() {
            let ranked = groups[key, default: []].sorted { $0.rank > $1.rank }
            if let best = ranked.first {
                winners.append(best)
                shadowed += ranked.dropFirst()
            }
        }
        return DriveScan(root: root, books: winners, shadowed: shadowed)
    }

    static func sameName(_ lhs: String, _ rhs: String) -> Bool {
        lhs.compare(rhs, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
    }

    // MARK: - File system

    private func zims(in directory: URL, folder: DriveFolder) -> [DriveEntry] {
        let files = listing(of: directory)
        var byLowercasedName: [String: URL] = [:]
        for file in files {
            byLowercasedName[file.lastPathComponent.lowercased()] = file
        }
        var result: [DriveEntry] = []
        for file in files {
            let fileName = file.lastPathComponent
            guard fileName.lowercased().hasSuffix(".zim"), !isDirectory(file) else { continue }
            let name = ZimFileName(fileName: fileName)
            let index = byLowercasedName[(name.stem + ".offidx").lowercased()]
                ?? byLowercasedName[(fileName + ".offidx").lowercased()]
            result.append(DriveEntry(url: file, folder: folder, name: name, indexURL: index, sizeBytes: size(of: file)))
        }
        return result
    }

    /// Directory listing without hidden files. Also drops `._name` AppleDouble files a Mac leaves on exFAT.
    private func listing(of directory: URL) -> [URL] {
        let keys: [URLResourceKey] = [.isDirectoryKey, .fileSizeKey]
        let urls = (try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles])) ?? []
        return urls.filter { !$0.lastPathComponent.hasPrefix(".") }
    }

    private func isDirectory(_ url: URL) -> Bool {
        var isDir: ObjCBool = false
        return fileManager.fileExists(atPath: url.path, isDirectory: &isDir) && isDir.boolValue
    }

    private func size(of url: URL) -> Int64 {
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
        return Int64(size)
    }
}
