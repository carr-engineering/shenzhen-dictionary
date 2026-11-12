import ComposeApp
import SwiftUI
import Translation
import UIKit

/// The iOS entry point: hosts the shared Compose UI and forwards the system callbacks that only
/// exist on the Swift side — links, Home Screen quick actions and Apple's translation sheet.
@main
struct SZDictApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            RootView()
                // Universal Links (https://szdict.com/…) and the szdict: scheme (GLB-111).
                .onOpenURL { url in _ = MainViewControllerKt.handleIncomingUrl(url: url.absoluteString) }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    if let url = activity.webpageURL { _ = MainViewControllerKt.handleIncomingUrl(url: url.absoluteString) }
                }
        }
    }
}

private struct RootView: View {
    @StateObject private var translation = TranslationPresenter.shared

    var body: some View {
        // Compose draws edge to edge and applies the safe-area and keyboard insets itself.
        ComposeView()
            .ignoresSafeArea()
            .translationPresentation(isPresented: $translation.isPresented, text: translation.text)
    }
}

private struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

/// MED-032: the system translation sheet for a passage; the Google Translate page only when the
/// Translation framework does not support the language pair.
@MainActor
final class TranslationPresenter: ObservableObject {
    static let shared = TranslationPresenter()

    @Published var isPresented = false
    @Published var text = ""

    func install() {
        MainViewControllerKt.setTranslationPresenter { text, sourceTag in
            Task { @MainActor in await TranslationPresenter.shared.present(text: text, sourceTag: sourceTag) }
        }
    }

    private func present(text: String, sourceTag: String) async {
        let source: Locale.Language
        switch sourceTag {
        case "zh-CN": source = Locale.Language(identifier: "zh-Hans")
        case "zh-TW": source = Locale.Language(identifier: "zh-Hant")
        case "yue": source = Locale.Language(identifier: "yue-Hant")
        default: source = Locale.Language(identifier: sourceTag)
        }
        let status = await LanguageAvailability().status(from: source, to: Locale.Language(identifier: "en"))
        if status == .unsupported {
            MainViewControllerKt.openWebTranslation(text: text, sourceLanguageTag: sourceTag)
        } else {
            self.text = text
            isPresented = true
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        TranslationPresenter.shared.install()
        return true
    }

    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

/// Home Screen quick actions (SET-050…053) reach the app through the scene, cold or warm.
final class SceneDelegate: NSObject, UIWindowSceneDelegate {
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        if let item = connectionOptions.shortcutItem {
            // After the first frame, so the shell exists when the destination is selected.
            DispatchQueue.main.async { _ = MainViewControllerKt.handleShortcut(type: item.type) }
        }
    }

    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem, completionHandler: @escaping (Bool) -> Void) {
        completionHandler(MainViewControllerKt.handleShortcut(type: shortcutItem.type))
    }
}
