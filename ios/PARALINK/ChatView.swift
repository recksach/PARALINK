import SwiftUI

struct ChatView: View {
    @EnvironmentObject var model: AppModel
    @State private var draft = ""
    @State private var recording = false

    private var shown: [ChatMessage] {
        if let peer = model.chatWith {
            return model.messages.messages.filter {
                ($0.incoming && $0.senderId == peer) || (!$0.incoming && $0.peerId == peer)
            }
        }
        return model.messages.messages.filter { $0.peerId == nil }
    }

    var body: some View {
        VStack(spacing: 0) {
            if let peer = model.chatWith {
                HStack {
                    Image(systemName: "lock.fill").font(.caption).foregroundColor(.secondary)
                    Text(Lang.text("send_to_peer") + ": " + model.chatName(for: peer))
                        .font(.footnote).foregroundColor(.secondary).lineLimit(1)
                    Spacer()
                    Button {
                        model.chatWith = nil
                    } label: {
                        Text("✕").font(.subheadline).foregroundColor(.secondary)
                    }
                }
                .padding(.horizontal).padding(.top, 6)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(model.radarNodes) { node in
                            Button {
                                model.chatWith = node.id
                            } label: {
                                Text(node.name).font(.caption2).lineLimit(1)
                                    .padding(.horizontal, 10).padding(.vertical, 5)
                                    .background(Capsule().fill(node.id == peer ? Color(red: 0.16, green: 0.85, blue: 1.0).opacity(0.25) : Color(red: 0.08, green: 0.12, blue: 0.22)))
                            }
                        }
                    }
                    .padding(.horizontal)
                }
                .padding(.bottom, 6)
            }

            List {
                if shown.isEmpty {
                    Text(Lang.text("no_messages"))
                        .font(.caption).foregroundColor(.secondary)
                        .frame(maxWidth: .infinity).multilineTextAlignment(.center)
                        .listRowBackground(Color.clear)
                }
                ForEach(shown) { m in
                    MessageBubble(m: m)
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)

            let micEnabled = !recording
            HStack(spacing: 10) {
                TextField(Lang.text("chat_placeholder"), text: $draft, axis: .vertical)
                    .textFieldStyle(.roundedBorder).font(.callout)
                    .lineLimit(1...4)
                Button(action: { model.sendText(draft); draft = "" }) {
                    Image(systemName: "arrow.up.circle.fill").font(.title2)
                }
                .disabled(recording)
                .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
                ZStack {
                    if recording {
                        Text(Lang.text("voice_placeholder")).font(.caption.bold()).foregroundColor(.red)
                    } else {
                        Image(systemName: "mic.fill").font(.title2).foregroundColor(micEnabled ? Color.accentColor : Color.gray)
                    }
                }
                .frame(width: 44, height: 44)
                .background(Circle().fill(recording ? Color.red.opacity(0.2) : Color(red: 0.08, green: 0.12, blue: 0.22)))
                .overlay(Circle().stroke(recording ? Color.red : Color.clear, lineWidth: 2))
                .onTapGesture {
                    if recording {
                        let r = model.voice.stop()
                        recording = false
                        model.sendVoiceTo(model.chatWith, r)
                    } else {
                        recording = true
                        model.voice.start()
                    }
                }
            }
            .padding(.horizontal).padding(.vertical, 8)
        }
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(red: 0.02, green: 0.05, blue: 0.1).ignoresSafeArea())
    }
}

struct MessageBubble: View {
    let m: ChatMessage

    var body: some View {
        HStack {
            if m.incoming { Spacer(minLength: 40) }
            VStack(alignment: m.incoming ? .leading : .trailing, spacing: 3) {
                if m.incoming {
                    Text(m.senderName).font(.caption2).foregroundColor(Color(red: 0.16, green: 0.85, blue: 1.0))
                }
                Text(m.text).font(.body)
                if let tr = m.translatedText, m.translationStatus != .none, tr != m.text {
                    Text(tr).font(.caption).foregroundColor(.secondary)
                }
                Text(timeLabel(m.timestamp)).font(.system(size: 9)).foregroundColor(.secondary)
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 14)
                    .fill(m.incoming ? Color(red: 0.10, green: 0.14, blue: 0.24) : Color(red: 0.05, green: 0.30, blue: 0.58))
            )
            if !m.incoming { Spacer(minLength: 40) }
        }
    }

    private func timeLabel(_ ts: Int64) -> String {
        let d = Date(timeIntervalSince1970: TimeInterval(ts) / 1000)
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: d)
    }
}