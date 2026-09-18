import Foundation

enum Lang {
    static var current: String {
        if let stored = UserDefaults.standard.string(forKey: "paralink.lang") { return stored }
        return Locale.preferredLanguages.first?.lowercased().hasPrefix("ru") == true ? "ru" : "en"
    }
    static func set(_ l: String) { UserDefaults.standard.set(l, forKey: "paralink.lang") }
    static func text(_ key: String) -> String { table[key.lowercased()]?[current] ?? key }
}

private let table: [String: [String: String]] = [
    "autonomous_network": ["en": "AUTONOMOUS NETWORK", "ru": "АВТОНОМНАЯ СЕТЬ"],
    "tab_network": ["en": "Network", "ru": "Сеть"],
    "tab_chat": ["en": "Chat", "ru": "Чат"],
    "tab_radio": ["en": "Radio", "ru": "Рация"],
    "tab_wallet": ["en": "Wallet", "ru": "Кошелёк"],
    "tab_profile": ["en": "Profile", "ru": "Профиль"],
    "connected": ["en": "CONNECTED", "ru": "СВЯЗЬ ЕСТЬ"],
    "local_search": ["en": "SEARCHING…", "ru": "ПОИСК…"],
    "bluetooth_title": ["en": "BLUETOOTH", "ru": "BLUETOOTH"],
    "bluetooth_desc": ["en": "No internet? Phones talk directly over the local network or Bluetooth (Android).", "ru": "Нет интернета? Связь по общей сети Wi-Fi или Bluetooth (Android)."],
    "bluetooth_scan": ["en": "SCAN NETWORK", "ru": "НАЙТИ В СЕТИ"],
    "bluetooth_connect": ["en": "CONNECT", "ru": "ПОДКЛЮЧИТЬ"],
    "connect_ip": ["en": "CONNECT BY IP", "ru": "ПОДКЛЮЧЕНИЕ ПО IP"],
    "connect_ip_hint": ["en": "Phone IP (e.g. 192.168.1.20)", "ru": "IP телефона (например 192.168.1.20)"],
    "connect_ip_button": ["en": "CONNECT", "ru": "ПОДКЛЮЧИТЬ"],
    "nearby_devices": ["en": "NEARBY DEVICES", "ru": "УСТРОЙСТВА РЯДОМ"],
    "nearby_hint": ["en": "Keep PARALINK open on another phone in the same Wi-Fi.", "ru": "Открой PARALINK на втором телефоне в той же Wi-Fi-сети."],
    "mesh_nodes": ["en": "CONNECTED PEERS", "ru": "СВЯЗАННЫЕ ПИРЫ"],
    "radar_title": ["en": "RADAR", "ru": "РАДАР"],
    "radar_hint": ["en": "GPS OFF — using connection order", "ru": "GPS ВЫКЛ — порядок по соединению"],
    "how_1": ["en": "Radar: tap a blip = private chat with that person.", "ru": "Радар: тап по метке = личный чат с этим человеком."],
    "how_2": ["en": "Radar: hold a blip to record a voice wave, release to send to that person.", "ru": "Радар: зажми метку — голосовое, отпусти — отправка именно ему."],
    "how_3": ["en": "Chat is a private messenger — your nickname is what others see.", "ru": "Чат — личный мессенджер: твой ник видят все."],
    "how_4": ["en": "No internet required: Bluetooth or a shared Wi-Fi network.", "ru": "Интернет не обязателен: Bluetooth или общая Wi-Fi-сеть."],
    "chat_placeholder": ["en": "Message…", "ru": "Сообщение…"],
    "voice_placeholder": ["en": "Hold to record voice…", "ru": "Держи, чтобы записать голос…"],
    "balance_title": ["en": "BALANCE", "ru": "БАЛАНС"],
    "shop_title": ["en": "STORE", "ru": "МАГАЗИН"],
    "buy": ["en": "BUY", "ru": "КУПИТЬ"],
    "transfer_to": ["en": "Transfer to (peer ID)", "ru": "Перевод (ID пира)"],
    "transfer_amount": ["en": "Amount PARA", "ru": "Сумма PARA"],
    "transfer": ["en": "SEND", "ru": "ОТПРАВИТЬ"],
    "profile_name": ["en": "NICKNAME", "ru": "НИК"],
    "save_name": ["en": "SAVE NAME", "ru": "СОХРАНИТЬ ИМЯ"],
    "how_it_works": ["en": "HOW IT WORKS", "ru": "КАК ЭТО РАБОТАЕТ"],
    "guide_title": ["en": "HOW PARALINK WORKS", "ru": "КАК РАБОТАЕТ PARALINK"],
    "guide_1": ["en": "No internet is required: connect over Bluetooth or a shared Wi-Fi network.", "ru": "Интернет не обязателен: связь по Bluetooth или общей Wi-Fi-сети."],
    "guide_2": ["en": "Keep the Network screen open on both phones. Same network = found automatically.", "ru": "Держи открытым экран «Сеть» на обоих. Одна сеть = найдут друг друга сами."],
    "guide_3": ["en": "Radar: tap a blip = private chat, hold = voice to that person.", "ru": "Радар: тап по метке = личный чат, зажать = голосовое ему."],
    "guide_4": ["en": "Set any nickname in Profile — others will see it.", "ru": "В «Профиле» задай ник — его увидят другие."],
    "guide_5": ["en": "You earn PARA while linked. Spend it in the Store.", "ru": "За связь капают PARA. Трать их в магазине."],
    "guide_start": ["en": "START", "ru": "НАЧАТЬ"],
    "translation_unavailable": ["en": "Offline translation unavailable", "ru": "Офлайн-перевод недоступен"],
    "ptt_target": ["en": "▼ REC →", "ru": "▼ REC →"],
    "no_messages": ["en": "No messages yet. Tap a radar blip to start a private chat.", "ru": "Пока пусто. Тапни метку на радаре — начни личный чат."],
    "send_to_peer": ["en": "PRIVATE", "ru": "ЛИЧНЫЙ"],
    "peer_contacts": ["en": "OPEN CONTACT", "ru": "ОТКРЫТЬ КОНТАКТ"]
]