import AVFoundation
import Foundation

@objc(KeepAliveManager) final class KeepAliveManager: NSObject {

    @objc static let shared = KeepAliveManager()

    /// UserDefaults key. Mirrors the JS setting so the toggle, launch, and
    /// background paths all read the same source of truth.
    @objc static let preferenceKey = "syncthing.continuousBackgroundSync"

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var silentBuffer: AVAudioPCMBuffer?
    private var running = false
    private var observing = false

    private override init() {
        super.init()
    }

    // MARK: - Preference

    @objc static var isEnabled: Bool {
        UserDefaults.standard.bool(forKey: preferenceKey)
    }

    /// Persist the toggle and immediately reconcile the running state.
    @objc func setEnabled(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: KeepAliveManager.preferenceKey)
        applyFromPreference()
    }

    /// Reconcile the actual audio state with the stored preference.
    @objc func applyFromPreference() {
        runOnMain { [weak self] in self?.reconcile() }
    }

    /// Bring the audio state in line with the preference. Always on main.
    private func reconcile() {
        if KeepAliveManager.isEnabled {
            if running && engine.isRunning { return }
            stop()
            start()
        } else {
            stop()
        }
    }

    private func runOnMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread {
            block()
        } else {
            DispatchQueue.main.async(execute: block)
        }
    }

    // MARK: - Lifecycle

    @objc func start() {
        guard !running else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
        } catch {
            NSLog("KeepAliveManager: failed to activate audio session: %@", "\(error)")
            return
        }

        guard startEngine() else {
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
            return
        }

        addObservers()
        running = true
        NSLog("KeepAliveManager: started (continuous background sync ON)")
    }

    @objc func stop() {
        guard running || engine.isRunning else { return }
        running = false
        removeObservers()
        player.stop()
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        NSLog("KeepAliveManager: stopped (continuous background sync OFF)")
    }

    // MARK: - Engine

    private func startEngine() -> Bool {
        let format = engine.outputNode.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            NSLog("KeepAliveManager: invalid output format - cannot start engine")
            return false
        }

        if silentBuffer == nil || !(silentBuffer?.format.isEqual(format) ?? false) {
            // ~0.5s of silence, looped. A small zero-filled buffer keeps CPU negligible.
            let frames = AVAudioFrameCount(format.sampleRate * 0.5)
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else {
                return false
            }
            buffer.frameLength = frames  // freshly allocated PCM buffers are zero-filled == silence
            silentBuffer = buffer
        }

        if !engine.attachedNodes.contains(player) {
            engine.attach(player)
        }
        engine.connect(player, to: engine.mainMixerNode, format: format)
        engine.mainMixerNode.outputVolume = 0

        engine.prepare()
        do {
            try engine.start()
        } catch {
            NSLog("KeepAliveManager: engine start failed: %@", "\(error)")
            return false
        }

        if let buffer = silentBuffer {
            player.scheduleBuffer(buffer, at: nil, options: [.loops], completionHandler: nil)
        }
        player.play()
        return true
    }

    // MARK: - Interruptions

    private func addObservers() {
        guard !observing else { return }
        observing = true
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(handleInterruption(_:)),
                       name: AVAudioSession.interruptionNotification, object: nil)
        nc.addObserver(self, selector: #selector(handleMediaReset(_:)),
                       name: AVAudioSession.mediaServicesWereResetNotification, object: nil)
    }

    private func removeObservers() {
        guard observing else { return }
        observing = false
        NotificationCenter.default.removeObserver(self)
    }

    /// A call/Siri/other app taking audio focus interrupts us.
    @objc private func handleInterruption(_ note: Notification) {
        guard let info = note.userInfo,
              let raw = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            NSLog("KeepAliveManager: audio interrupted")
        case .ended:
            NSLog("KeepAliveManager: interruption ended - restarting")
            runOnMain { [weak self] in self?.reconcile() }
        @unknown default:
            break
        }
    }

    /// Media services can reset (rare). Engine + buffers are invalid afterward,
    /// so drop the buffer and rebuild from scratch.
    @objc private func handleMediaReset(_ note: Notification) {
        NSLog("KeepAliveManager: media services reset - rebuilding")
        runOnMain { [weak self] in
            self?.silentBuffer = nil
            self?.reconcile()
        }
    }
}
