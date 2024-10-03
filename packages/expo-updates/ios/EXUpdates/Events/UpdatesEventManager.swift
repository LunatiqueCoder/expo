//  Copyright © 2019 650 Industries. All rights reserved.

import ExpoModulesCore

public protocol UpdatesEventManager {
  /**
   The AppContext from expo-modules-core.
   This is optional, but required for expo-updates module events to work.
   */
  var appContext: AppContext? { get }
  var shouldEmitJsEvents: Bool { get }

  // swift is weird and doesn't allow assigning to a property's property if anything in the setter chain is get-only (let)
  // so we use setter methods here instead
  func setAppContext(appContext: AppContext?)
  func setShouldEmitJsEvents(shouldEmitJsEvents: Bool)

  func sendUpdateStateChangeEventToAppContext(_ eventType: UpdatesStateEventType, context: UpdatesStateContext)
}
