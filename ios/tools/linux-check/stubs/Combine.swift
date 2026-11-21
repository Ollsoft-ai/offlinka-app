// Stand-in for the parts of Combine the app uses (type-checking on Linux only).
@_exported import Foundation

public protocol Publisher {
    associatedtype Output
    associatedtype Failure: Error
}

extension NotificationCenter {
    public struct NotificationPublisher: Publisher {
        public typealias Output = Notification
        public typealias Failure = Never
    }

    public func publisher(for name: Notification.Name, object: AnyObject? = nil) -> NotificationPublisher { NotificationPublisher() }
}
