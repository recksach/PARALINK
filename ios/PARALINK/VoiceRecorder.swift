import Foundation
import AVFoundation

final class VoiceIO: NSObject, AVAudioPlayerDelegate {
    private var recorder: AVAudioRecorder?
    private var recordUrl: URL?
    private var player: AVAudioPlayer?
    private var completion: (() -> Void)?
    private(set) var isRecording = false

    func start() {
        stopPlayback()
        try? AVAudioSession.sharedInstance().setCategory(.record, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("voice-\(UUID().uuidString).wav")
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: 16000.0,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false
        ]
        do {
            let r = try AVAudioRecorder(url: url, settings: settings)
            r.prepareToRecord()
            r.record()
            recorder = r
            recordUrl = url
            isRecording = true
        } catch {
            recorder = nil
        }
    }

    func stop() -> (wavB64: String, durationMs: Int64) {
        guard let r = recorder else { return ("", 0) }
        let dur = Int64(r.currentTime * 1000)
        r.stop()
        recorder = nil
        isRecording = false
        guard let url = recordUrl, let data = try? Data(contentsOf: url) else { return ("", dur) }
        return (data.base64EncodedString(), dur)
    }

    func play(wavBase64: String, done: @escaping () -> Void) {
        stopPlayback()
        guard let data = Data(base64Encoded: wavBase64) else { done(); return }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
        do {
            let p = try AVAudioPlayer(data: data)
            p.delegate = self
            player = p
            completion = done
            p.play()
        } catch {
            done()
        }
    }

    func stopPlayback() {
        player?.stop()
        player = nil
        let c = completion
        completion = nil
        c?()
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        self.player = nil
        let c = completion
        completion = nil
        c?()
    }
}