//  Copyright © 2019 650 Industries. All rights reserved.

import ExpoModulesCore

internal class NoOpUpdatesEventManager: UpdatesEventManager {
  internal private(set) weak var appContext: AppContext?
  internal private(set) var shouldEmitJsEvents: Bool = false

  internal func setAppContext(appContext: AppContext?) {
    self.appContext = appContext
  }

  internal func setShouldEmitJsEvents(shouldEmitJsEvents: Bool) {
    self.shouldEmitJsEvents = shouldEmitJsEvents
  }

  internal func sendUpdateStateChangeEventToAppContext(_ eventType: UpdatesStateEventType, context: UpdatesStateContext) {}
}
