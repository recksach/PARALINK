import SwiftUI
import CoreLocation

struct NetworkView: View {
    @EnvironmentObject var model: AppModel
    @State private var ip = ""
    @State private var pttTarget: String?

    private let grid = [Color(red: 0.14, green: 0.26, blue: 0.44), Color(red: 0.10, green: 0.19, blue: 0.34)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("PARALINK").font(.title2.bold())
                        Text(Lang.text("autonomous_network")).font(.caption).foregroundColor(.secondary)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 4) {
                        Text(model.connectedCount > 0 ? Lang.text("connected") : Lang.text("local_search"))
                            .font(.caption2.bold())
                            .foregroundColor(model.connectedCount > 0 ? Color(red: 0.2, green: 0.9, blue: 0.5) : Color.orange)
                        Text("\(model.connectedCount) " + Lang.text("mesh_nodes"))
                            .font(.caption2).foregroundColor(.secondary)
                    }
                }

                RadarCanvas(
                    nodes: model.radarNodes,
                    myLat: model.myLat,
                    myLon: model.myLon,
                    heading: model.heading,
                    onTap: { id in
                        model.chatWith = id
                        model.tab = 1
                    },
                    onPtt: { id, isPressed in
                        if isPressed {
                            pttTarget = id
                            model.voice.start()
                        } else {
                            pttTarget = nil
                            let r = model.voice.stop()
                            model.sendVoiceTo(id, r)
                        }
                    }
                )

                if pttTarget != nil {
                    HStack {
                        Circle().fill(Color.red).frame(width: 8, height: 8)
                        Text(Lang.text("ptt_target") + " " + model.chatName(for: pttTarget ?? "")).font(.caption.bold()).foregroundColor(.red)
                    }
                }

                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Image(systemName: "dot.radiowaves.left.and.right").foregroundColor(Color(red: 0.16, green: 0.85, blue: 1.0))
                        Text(Lang.text("bluetooth_title")).font(.subheadline.bold())
                    }
                    Text(Lang.text("bluetooth_desc")).font(.caption).foregroundColor(.secondary)
                    TextField(Lang.text("connect_ip_hint"), text: $ip)
                        .textFieldStyle(.roundedBorder)
                        .font(.callout)
                        .keyboardType(.numbersAndPunctuation)
                    Button(action: connectByIp) {
                        Text(Lang.text("connect_ip_button")).font(.callout.bold()).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(ip.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 16).fill(Color(red: 0.05, green: 0.09, blue: 0.16)))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(red: 0.13, green: 0.3, blue: 0.5), lineWidth: 1))

                if !model.radarNodes.isEmpty {
                    Text(Lang.text("mesh_nodes")).font(.subheadline.bold())
                    ForEach(model.radarNodes) { node in
                        HStack {
                            Circle().fill(node.gold ? Color.orange : Color.cyan).frame(width: 10, height: 10)
                            Text(node.gold ? "★ " : "").foregroundColor(Color.orange)
                            Text(node.name).font(.callout)
                            Spacer()
                            Text(node.lat != 0 ? String(format: "%.0f m", haversine(a: (model.myLat, model.myLon), b: (node.lat, node.lon))) : "")
                                .font(.caption).foregroundColor(.secondary)
                            Button {
                                model.chatWith = node.id
                                model.tab = 1
                            } label: {
                                Text(Lang.text("peer_contacts")).font(.caption2.bold())
                            }
                            .buttonStyle(.bordered)
                            .tint(Color(red: 0.16, green: 0.85, blue: 1.0))
                        }
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 12).fill(Color(red: 0.05, green: 0.09, blue: 0.16)))
                    }
                } else {
                    VStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text(Lang.text("nearby_hint")).font(.caption).foregroundColor(.secondary).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 20)
                }

                if let err = model.lastError {
                    Text(err).font(.caption).foregroundColor(.red)
                }
            }
            .padding()
        }
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(red: 0.02, green: 0.05, blue: 0.1).ignoresSafeArea())
    }

    private func connectByIp() {
        let host = ip.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !host.isEmpty else { return }
        model.session.connectTo(host: host, port: AppConfig.tcpPort) { ok in
            DispatchQueue.main.async { model.refresh() }
        }
        ip = ""
    }
}

struct RadarCanvas: View {
    let nodes: [RadarNode]
    let myLat: Double
    let myLon: Double
    let heading: Double
    var onTap: (String) -> Void = { _ in }
    var onPtt: (String, Bool) -> Void = { _, _ in }

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            ZStack {
                Canvas { ctx, sz in
                    let c = CGPoint(x: sz.width / 2, y: sz.height / 2)
                    let R = min(sz.width, sz.height) * 0.42
                    for i in 1...3 {
                        let rr = R * CGFloat(i) / 3
                        ctx.stroke(Path(ellipseIn: CGRect(x: c.x - rr, y: c.y - rr, width: rr * 2, height: rr * 2)),
                                   with: .color(Color(red: 0.14, green: 0.26, blue: 0.44)), lineWidth: 1)
                    }
                    var cross = Path()
                    cross.move(to: CGPoint(x: c.x, y: c.y - R))
                    cross.addLine(to: CGPoint(x: c.x, y: c.y + R))
                    cross.move(to: CGPoint(x: c.x - R, y: c.y))
                    cross.addLine(to: CGPoint(x: c.x + R, y: c.y))
                    ctx.stroke(cross, with: .color(Color(red: 0.10, green: 0.19, blue: 0.34)), lineWidth: 1)
                    var wedge = Path()
                    wedge.move(to: c)
                    wedge.addArc(center: c, radius: R, startAngle: .degrees(90 - heading - 22),
                                 endAngle: .degrees(90 - heading + 22), clockwise: false)
                    ctx.fill(wedge, with: .color(Color(red: 0.09, green: 0.35, blue: 0.62).opacity(0.16)))
                    let nTop = CGPoint(x: c.x, y: c.y - R)
                    ctx.fill(Path(CGRect(x: nTop.x - 4, y: nTop.y - 12, width: 8, height: 12)),
                             with: .color(Color.white.opacity(0.5)))
                    ctx.drawLayer { ctx in
                        ctx.fill(Path(CGRect(x: 0, y: 0, width: sz.width, height: sz.height)),
                                 with: .color(.clear))
                    }
                }
                ForEach(nodes) { node in
                    Blip(node: node, pos: position(node, in: size))
                        .onTapGesture { onTap(node.id) }
                        .gesture(LongPressGesture(minimumDuration: 0.6)
                            .onChanged { v in if v { onPtt(node.id, true) } }
                            .onEnded { _ in onPtt(node.id, false) })
                }
            }
        }
        .frame(height: 300)
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color(red: 0.13, green: 0.3, blue: 0.5), lineWidth: 1))
        .background(RoundedRectangle(cornerRadius: 24).fill(Color(red: 0.03, green: 0.07, blue: 0.13)))
    }

    private func position(_ node: RadarNode, in size: CGSize) -> CGPoint {
        let c = CGPoint(x: size.width / 2, y: size.height / 2)
        let maxR = min(size.width, size.height) * 0.38
        var angDeg = 0.0
        var distM = 400.0
        if myLat != 0 && myLon != 0 && node.lat != 0 && node.lon != 0 {
            angDeg = bearingDeg(from: (myLat, myLon), to: (node.lat, node.lon)) - heading
            distM = haversine(a: (myLat, myLon), b: (node.lat, node.lon))
        } else {
            angDeg = Double(abs(node.id.hashValue) % 360)
        }
        let scale = min(1.0, distM / 1500.0)
        let r = maxR * (0.16 + scale * 0.84)
        let rad = angDeg * .pi / 180
        return CGPoint(x: c.x + CGFloat(sin(rad)) * r, y: c.y - CGFloat(cos(rad)) * r)
    }
}

struct Blip: View {
    let node: RadarNode
    let pos: CGPoint

    var body: some View {
        VStack(spacing: 3) {
            Text(node.name)
                .font(.system(size: 10, weight: .semibold)).foregroundColor(.white)
                .padding(.horizontal, 6).padding(.vertical, 2)
                .background(Capsule().fill(Color(red: 0.05, green: 0.10, blue: 0.20).opacity(0.92)))
            ZStack {
                Circle().fill(node.gold ? Color.orange : Color.cyan).frame(width: 15, height: 15)
                    .overlay(Circle().stroke(Color.white.opacity(0.9), lineWidth: 1.5))
                if node.gold {
                    Text("★").font(.system(size: 9, weight: .bold)).foregroundColor(.black)
                }
            }
        }
        .position(pos)
    }
}

private func haversine(a: (Double, Double), b: (Double, Double)) -> Double {
    let R = 6_371_000.0
    let dLat = (b.0 - a.0) * .pi / 180
    let dLon = (b.1 - a.1) * .pi / 180
    let la1 = a.0 * .pi / 180
    let la2 = b.0 * .pi / 180
    let h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * R * asin(min(1, sqrt(h)))
}

private func bearingDeg(from a: (Double, Double), to b: (Double, Double)) -> Double {
    let la1 = a.0 * .pi / 180
    let la2 = b.0 * .pi / 180
    let dLon = (b.1 - a.1) * .pi / 180
    let y = sin(dLon) * cos(la2)
    let x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
    let br = atan2(y, x)
    return (br * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
}