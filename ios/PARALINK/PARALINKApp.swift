import SwiftUI

@main
struct PARALINKApp: App {
    @StateObject private var model = AppModel()

    init() {
        model.start()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        TabView(selection: $model.tab) {
            NavigationView { NetworkView() }
                .tabItem { Label(Lang.text("tab_network"), systemImage: "network") }
                .tag(0)
            NavigationView { ChatView() }
                .tabItem { Label(Lang.text("tab_chat"), systemImage: "message") }
                .tag(1)
            NavigationView { RadioView() }
                .tabItem { Label(Lang.text("tab_radio"), systemImage: "mic") }
                .tag(2)
            NavigationView { WalletView() }
                .tabItem { Label(Lang.text("tab_wallet"), systemImage: "wallet") }
                .tag(3)
            NavigationView { ProfileView() }
                .tabItem { Label(Lang.text("tab_profile"), systemImage: "person") }
                .tag(4)
        }
        .preferredColorScheme(.dark)
        .tint(Color(red: 0.18, green: 0.49, blue: 1.0))
        .sheet(isPresented: Binding(get: { model.showGuide }, set: { _ in })) {
            GuideView().environmentObject(model)
        }
    }
}

struct GuideView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    private let items = ["guide_1", "guide_2", "guide_3", "guide_4", "guide_5"]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(Lang.text("guide_title")).font(.title2.bold())
            ForEach(items.indices, id: \.self) { i in
                HStack(alignment: .top, spacing: 10) {
                    Text("\(i + 1).").font(.headline).foregroundColor(Color(red: 0.16, green: 0.85, blue: 1.0))
                    Text(Lang.text(items[i])).font(.body)
                }
            }
            Spacer()
            Button {
                model.markGuided()
                dismiss()
            } label: {
                Text(Lang.text("guide_start")).font(.headline).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(24)
    }
}