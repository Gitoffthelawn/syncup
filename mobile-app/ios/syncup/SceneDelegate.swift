import Expo
import React
import UIKit

/// UIScene lifecycle adoption.
///
/// Apps linked against the iOS 27 SDK must adopt the UIScene lifecycle. On iOS 27
/// the legacy UIApplicationDelegate-owned window path traps at launch inside
/// `_UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption` (EXC_BREAKPOINT),
/// so the app never gets past scene creation.
///
/// The scene owns the UIWindow and starts React Native into it. App-level
/// concerns (Go daemon, BGTaskScheduler registration, notifications, App
/// Shortcuts) stay in AppDelegate.didFinishLaunching, which still runs before
/// the first scene connects. Foreground/background transitions are forwarded to
/// the AppDelegate handlers so ExpoAppDelegate subscribers keep receiving them;
/// UIKit stops calling those app-level methods once a scene manifest exists.
public class SceneDelegate: UIResponder, UIWindowSceneDelegate {
  public var window: UIWindow?

  private var appDelegate: AppDelegate? {
    UIApplication.shared.delegate as? AppDelegate
  }

  public func scene(
    _ scene: UIScene,
    willConnectTo session: UISceneSession,
    options connectionOptions: UIScene.ConnectionOptions
  ) {
    guard let windowScene = scene as? UIWindowScene else { return }
    guard let appDelegate = appDelegate, let factory = appDelegate.reactNativeFactory else {
      NSLog("SceneDelegate: AppDelegate or ReactNativeFactory missing; cannot start React Native")
      return
    }

    let window = UIWindow(windowScene: windowScene)
    self.window = window
    appDelegate.window = window

    factory.startReactNative(
      withModuleName: "main",
      in: window,
      launchOptions: appDelegate.launchOptions)

    // Deep links / user activities that arrived with the cold launch are no
    // longer delivered via application(_:open:options:) under the scene lifecycle.
    if !connectionOptions.urlContexts.isEmpty {
      self.scene(scene, openURLContexts: connectionOptions.urlContexts)
    }
    for activity in connectionOptions.userActivities {
      self.scene(scene, continue: activity)
    }
  }

  public func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
    guard let appDelegate = appDelegate else { return }
    for context in URLContexts {
      var options: [UIApplication.OpenURLOptionsKey: Any] = [
        .openInPlace: context.options.openInPlace
      ]
      if let source = context.options.sourceApplication {
        options[.sourceApplication] = source
      }
      if let annotation = context.options.annotation {
        options[.annotation] = annotation
      }
      _ = appDelegate.application(UIApplication.shared, open: context.url, options: options)
    }
  }

  public func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
    guard let appDelegate = appDelegate else { return }
    _ = appDelegate.application(UIApplication.shared, continue: userActivity) { _ in }
  }

  // MARK: - Lifecycle forwarding

  public func sceneDidBecomeActive(_ scene: UIScene) {
    appDelegate?.applicationDidBecomeActive(UIApplication.shared)
  }

  public func sceneWillResignActive(_ scene: UIScene) {
    appDelegate?.applicationWillResignActive(UIApplication.shared)
  }

  public func sceneDidEnterBackground(_ scene: UIScene) {
    appDelegate?.applicationDidEnterBackground(UIApplication.shared)
  }

  public func sceneWillEnterForeground(_ scene: UIScene) {
    appDelegate?.applicationWillEnterForeground(UIApplication.shared)
  }
}
