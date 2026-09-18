import SwiftUI

struct RadioView: View {
    @EnvironmentObject var model: AppModel
    @State private var recording = false

    var body: some View {
        VStack(spacing: 16) {
            ZStack {
                if recording {
                    Text(Lang.text("voice_placeholder")).font(.headline.bold()).foregroundColor(.red)
                } else {
                    VStack(spacing: 6) {
                        Image(systemName: "mic.fill").font(.system(size: 34)).foregroundColor(.white)
                        Text(Lang.text("bluetooth_title")).font(.headline.bold())
                    }
                }
            }
            .frame(maxWidth: .infinity, minHeight: 140)
            .background(
                RoundedRectangle(cornerRadius: 22)
                    .fill(recording ? Color.red.opacity(0.25) : Color(red: 0.05, green: 0.09, blue: 0.16))
            )
            .overlay(RoundedRectangle(cornerRadius: 22).stroke(recording ? Color.red : Color(red: 0.13, green: 0.3, blue: 0.5), lineWidth: 1))
            .onTapGesture {
                if recording {
                    let r = model.voice.stop()
                    recording = false
                    model.sendVoiceTo(nil, r)
                } else {
                    recording = true
                    model.voice.start()
                }
            }

            Text(Lang.text("voice_placeholder")).font(.caption).foregroundColor(.secondary)

            List {
                if model.messages.voices.isEmpty {
                    Text(Lang.text("no_messages"))
                        .font(.caption).foregroundColor(.secondary)
                        .frame(maxWidth: .infinity).multilineTextAlignment(.center)
                        .listRowBackground(Color.clear)
                }
                ForEach(model.messages.voices) { v in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Button {
                                model.play(v)
                            } label: {
                                Image(systemName: model.playingVoiceId == v.id ? "stop.fill" : "play.fill")
                                    .font(.title3)
                            }
                            .buttonStyle(.bordered)
                            .tint(model.playingVoiceId == v.id ? Color.red : Color(red: 0.16, green: 0.85, blue: 1.0))
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(v.incoming ? v.senderName : model.myName) (\(v.incoming ? "" : "→ ")\(v.peerId != nil ? "PRIV" : "ALL"))")
                                    .font(.caption).foregroundColor(.secondary)
                                Text("\(Int(v.durationMs) / 1000)s").font(.caption2).foregroundColor(.secondary)
                            }
                            Spacer()
                        }
                        Waveform(durationMs: v.durationMs)
                            .frame(height: 28)
                            .foregroundColor(model.playingVoiceId == v.id ? Color.red : Color(red: 0.16, green: 0.85, blue: 1.0))
                    }
                    .padding(.vertical, 4)
                    .listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
        }
        .padding()
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(red: 0.02, green: 0.05, blue: 0.1).ignoresSafeArea())
    }
}

struct Waveform: View {
    let durationMs: Int64
    private let n = 24

    var body: some View {
        GeometryReader { geo in
            let w = (geo.size.width - CGFloat(n) * 2) / CGFloat(n)
            HStack(spacing: 2) {
                ForEach(0..<n, id: \.self) { i in
                    RoundedRectangle(cornerRadius: 1)
                        .frame(width: max(1, w), height: CGFloat(4 + abs((i * 7) % 18 - 9)))
                }
            }
            .frame(maxHeight: .infinity, alignment: .center)
        }
    }
}

struct WalletView: View {
    @EnvironmentObject var model: AppModel
    @State private var peer = ""
    @State private var amount = ""
    @State private var note = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(Lang.text("balance_title")).font(.caption).foregroundColor(.secondary)
                    Text(String(format: "%.2f PARA", model.wallet))
                        .font(.system(size: 38, weight: .bold, design: .monospaced))
                        .foregroundColor(Color(red: 0.2, green: 0.9, blue: 0.5))
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 18).fill(Color(red: 0.04, green: 0.10, blue: 0.08)))
                .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color(red: 0.1, green: 0.4, blue: 0.25), lineWidth: 1))

                VStack(alignment: .leading, spacing: 10) {
                    Text("TRANSFER").font(.subheadline.bold())
                    if !model.radarNodes.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(model.radarNodes) { node in
                                    Button {
                                        peer = node.id
                                    } label: {
                                        Text(node.name).font(.caption2).lineLimit(1)
                                            .padding(.horizontal, 10).padding(.vertical, 5)
                                            .background(Capsule().fill(Color(red: 0.08, green: 0.12, blue: 0.22)))
                                    }
                                }
                            }
                        }
                    }
                    TextField("peer ID", text: $peer).textFieldStyle(.roundedBorder).font(.callout)
                    TextField("0.00", text: $amount).textFieldStyle(.roundedBorder).font(.callout)
                        .keyboardType(.decimalPad)
                    Button {
                        guard let amt = Double(amount.replacingOccurrences(of: ",", with: ".")), !peer.isEmpty else { return }
                        model.transfer(to: peer, amount: amt, note: note)
                        amount = ""
                    } label: {
                        Text(Lang.text("transfer")).font(.callout.bold()).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(peer.isEmpty || Double(amount.replacingOccurrences(of: ",", with: ".")) == nil)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 16).fill(Color(red: 0.05, green: 0.09, blue: 0.16)))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(red: 0.13, green: 0.3, blue: 0.5), lineWidth: 1))

                Text(Lang.text("shop_title")).font(.subheadline.bold())
                ForEach(model.shop.items) { item in
                    HStack {
                        Image(systemName: item.icon).font(.title3).foregroundColor(item.icon == "star" ? Color.yellow : Color(red: 0.16, green: 0.85, blue: 1.0))
                            .frame(width: 32)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.title).font(.callout)
                            Text(String(format: "%.0f PARA", item.price)).font(.caption).foregroundColor(.secondary)
                        }
                        Spacer()
                        if model.shop.owns(item.id) {
                            Text("✓").font(.headline).foregroundColor(Color(red: 0.2, green: 0.9, blue: 0.5))
                        } else {
                            Button {
                                model.buy(item)
                            } label: {
                                Text(Lang.text("buy")).font(.caption.bold())
                            }
                            .buttonStyle(.bordered)
                            .tint(model.wallet >= item.price ? Color(red: 0.16, green: 0.85, blue: 1.0) : Color.gray)
                            .disabled(model.wallet < item.price)
                        }
                    }
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color(red: 0.05, green: 0.09, blue: 0.16)))
                }

                Text("HISTORY").font(.subheadline.bold())
                if model.ledger.txs.isEmpty {
                    Text("—").font(.caption).foregroundColor(.secondary)
                } else {
                    ForEach(model.ledger.txs.suffix(20)) { tx in
                        HStack {
                            Text(tx.kind == "debit" ? "→" : "←").font(.caption).foregroundColor(.secondary)
                            Text(String(format: "%+.2f", tx.amount)).font(.callout.monospacedDigit())
                                .foregroundColor(tx.amount >= 0 ? Color(red: 0.2, green: 0.9, blue: 0.5) : .red)
                            Spacer()
                            Text(shortId(tx.peerId)).font(.caption).foregroundColor(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            .padding()
        }
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(red: 0.02, green: 0.05, blue: 0.1).ignoresSafeArea())
    }

    private func shortId(_ id: String) -> String {
        guard id.count > 8 else { return id }
        return String(id.prefix(8))
    }
}

struct ProfileView: View {
    @EnvironmentObject var model: AppModel
    @State private var name = ""
    @State private var lang: String = Lang.current
    private let how = ["how_1", "how_2", "how_3", "how_4"]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    ZStack {
                        Circle().fill(Color(red: 0.11, green: 0.25, blue: 0.45)).frame(width: 56, height: 56)
                        Text(String(model.myName.prefix(1)).uppercased())
                            .font(.title2.bold()).foregroundColor(Color(red: 0.16, green: 0.85, blue: 1.0))
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(model.myName).font(.headline)
                        Text(shortId(model.identity.nodeId)).font(.caption).foregroundColor(.secondary)
                    }
                    Spacer()
                }

                VStack(alignment: .leading, spacing: 10) {
                    Text(Lang.text("profile_name")).font(.subheadline.bold())
                    TextField(model.myName, text: $name).textFieldStyle(.roundedBorder).font(.callout)
                    Button {
                        model.rename(name)
                    } label: {
                        Text(Lang.text("save_name")).font(.callout.bold()).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 16).fill(Color(red: 0.05, green: 0.09, blue: 0.16)))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(red: 0.13, green: 0.3, blue: 0.5), lineWidth: 1))

                VStack(alignment: .leading, spacing: 8) {
                    Text("Language / Язык").font(.subheadline.bold())
                    Picker("", selection: $lang) {
                        Text("English").tag("en")
                        Text("Русский").tag("ru")
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: lang) { newValue in
                        Lang.set(newValue)
                    }
                    Text("Some labels refresh now, others after app restart.")
                        .font(.caption2).foregroundColor(.secondary)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 16).fill(Color(red: 0.05, green: 0.09, blue: 0.16)))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(red: 0.13, green: 0.3, blue: 0.5), lineWidth: 1))

                Text(Lang.text("how_it_works")).font(.subheadline.bold())
                ForEach(how, id: \.self) { key in
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "circle.fill").font(.system(size: 6)).foregroundColor(Color(red: 0.16, green: 0.85, blue: 1.0)).padding(.top, 5)
                        Text(Lang.text(key)).font(.caption).foregroundColor(.secondary)
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 16).fill(Color(red: 0.05, green: 0.09, blue: 0.16)))
            }
            .padding()
        }
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(red: 0.02, green: 0.05, blue: 0.1).ignoresSafeArea())
    }

    private func shortId(_ id: String) -> String {
        guard id.count > 8 else { return id }
        return String(id.prefix(8))
    }
}