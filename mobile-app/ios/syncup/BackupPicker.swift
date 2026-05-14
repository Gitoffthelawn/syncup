import Foundation
import UIKit
import UniformTypeIdentifiers

@objc(BackupPicker) final class BackupPicker: NSObject {
    @objc static let shared = BackupPicker()

    private let queue = DispatchQueue(label: "com.siddarthkay.syncup.backupPicker")
    private var pendingDelegates: [ObjectIdentifier: NSObject] = [:]

    private override init() {
        super.init()
    }

    @objc func exportFileBlocking(sourcePath: String) -> String {
        if Thread.isMainThread {
            NSLog("BackupPicker: exportFileBlocking called on main thread; would deadlock")
            return ""
        }
        let semaphore = DispatchSemaphore(value: 0)
        var result: String = ""

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { semaphore.signal(); return }
            guard let presenter = self.topPresentingViewController() else {
                NSLog("BackupPicker: no presenter VC for export")
                semaphore.signal()
                return
            }
            let url = URL(fileURLWithPath: sourcePath)
            let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
            let delegate = BackupPickerDelegate { urls in
                defer { semaphore.signal() }
                guard let dest = urls.first else { return }
                result = dest.path
            }
            picker.delegate = delegate
            let key = ObjectIdentifier(picker)
            self.queue.sync { self.pendingDelegates[key] = delegate }
            delegate.onComplete = { [weak self] in
                self?.queue.sync { self?.pendingDelegates.removeValue(forKey: key) }
            }
            presenter.present(picker, animated: true, completion: nil)
        }

        semaphore.wait()
        return result
    }

    @objc func importFileBlocking(destinationPath: String) -> String {
        if Thread.isMainThread {
            NSLog("BackupPicker: importFileBlocking called on main thread; would deadlock")
            return ""
        }
        let semaphore = DispatchSemaphore(value: 0)
        var result: String = ""

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { semaphore.signal(); return }
            guard let presenter = self.topPresentingViewController() else {
                NSLog("BackupPicker: no presenter VC for import")
                semaphore.signal()
                return
            }
            let picker: UIDocumentPickerViewController
            if #available(iOS 14.0, *) {
                let types: [UTType] = [UTType.zip, UTType.data]
                picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)
            } else {
                picker = UIDocumentPickerViewController(
                    documentTypes: ["public.zip-archive", "public.data"],
                    in: .import
                )
            }
            picker.allowsMultipleSelection = false
            let delegate = BackupPickerDelegate { urls in
                defer { semaphore.signal() }
                guard let src = urls.first else { return }
                let didStart = src.startAccessingSecurityScopedResource()
                defer { if didStart { src.stopAccessingSecurityScopedResource() } }
                do {
                    let dst = URL(fileURLWithPath: destinationPath)
                    let parent = dst.deletingLastPathComponent()
                    try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
                    if FileManager.default.fileExists(atPath: dst.path) {
                        try FileManager.default.removeItem(at: dst)
                    }
                    try FileManager.default.copyItem(at: src, to: dst)
                    result = dst.path
                } catch {
                    NSLog("BackupPicker: copy failed: %@", "\(error)")
                }
            }
            picker.delegate = delegate
            let key = ObjectIdentifier(picker)
            self.queue.sync { self.pendingDelegates[key] = delegate }
            delegate.onComplete = { [weak self] in
                self?.queue.sync { self?.pendingDelegates.removeValue(forKey: key) }
            }
            presenter.present(picker, animated: true, completion: nil)
        }

        semaphore.wait()
        return result
    }

    private func topPresentingViewController() -> UIViewController? {
        var window: UIWindow?
        if #available(iOS 13.0, *) {
            window = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap { $0.windows }
                .first(where: { $0.isKeyWindow })
        }
        if window == nil {
            window = UIApplication.shared.windows.first(where: { $0.isKeyWindow })
                ?? UIApplication.shared.windows.first
        }
        var top = window?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
}

private final class BackupPickerDelegate: NSObject, UIDocumentPickerDelegate {
    private let onPick: ([URL]) -> Void
    var onComplete: (() -> Void)?

    init(onPick: @escaping ([URL]) -> Void) {
        self.onPick = onPick
    }

    func documentPicker(_ controller: UIDocumentPickerViewController,
                        didPickDocumentsAt urls: [URL]) {
        onPick(urls)
        onComplete?()
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        onPick([])
        onComplete?()
    }
}
