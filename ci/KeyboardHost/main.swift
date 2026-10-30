import UIKit

/// CI-only host app: focuses a text field so the simulator shows the current keyboard,
/// then prints which keyboard is up. Used by ci/keyboard-smoke.sh.
final class ViewController: UIViewController {
    private let field = UITextField()
    /// Only the globe/dictation strip is on screen until the extension draws its keys.
    private var keyboardHeight: CGFloat = 0

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        field.frame = CGRect(x: 20, y: 120, width: 320, height: 44)
        field.borderStyle = .roundedRect
        field.placeholder = "Tin nhắn"
        view.addSubview(field)

        let center = NotificationCenter.default
        center.addObserver(forName: UIResponder.keyboardDidChangeFrameNotification, object: nil, queue: .main) { [weak self] note in
            let frame = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue ?? .zero
            self?.keyboardHeight = frame.height
            self?.report("keyboardDidChangeFrame frame=\(frame)")
        }
        center.addObserver(forName: UIResponder.keyboardDidHideNotification, object: nil, queue: .main) { [weak self] _ in
            self?.report("keyboardDidHide")
        }
        center.addObserver(forName: UITextInputMode.currentInputModeDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.report("inputModeDidChange")
        }
        // Every change, so CI can check what the typing benchmark typed.
        center.addObserver(forName: UITextField.textDidChangeNotification, object: field, queue: .main) { [weak self] _ in
            print("FIELD_TEXT=\(self?.field.text ?? "")")
            fflush(stdout)
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        report("before focus")
        field.becomeFirstResponder()
        for delay in [1.0, 3.0, 6.0, 10.0] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in self?.report("t+\(Int(delay))s") }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 12) { [weak self] in
            guard let self else { return }
            print("KEYBOARD_HEIGHT=\(Int(keyboardHeight))")
            print("FIELD_TEXT=\(field.text ?? "")")
            fflush(stdout)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
            guard let self else { return }
            print("FIELD_TEXT=\(field.text ?? "")")
            fflush(stdout)
        }
    }

    private func report(_ event: String) {
        let active = UITextInputMode.activeInputModes.map(Self.identifier)
        let current = field.textInputMode.map(Self.identifier) ?? "nil"
        print("HOST \(event) | firstResponder=\(field.isFirstResponder) current=\(current) active=\(active)")
        fflush(stdout)
    }

    private static func identifier(_ mode: UITextInputMode) -> String {
        let key = NSSelectorFromString("identifier")
        guard mode.responds(to: key), let id = mode.value(forKey: "identifier") as? String else {
            return mode.primaryLanguage ?? "?"
        }
        return id
    }
}

final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = ViewController()
        window?.makeKeyAndVisible()
        return true
    }
}

UIApplicationMain(CommandLine.argc, CommandLine.unsafeArgv, nil, NSStringFromClass(AppDelegate.self))
