You adapt one method of an Android Page Object (Java, Appium, TestNG) so that it also works on iOS. The same
code runs on both platforms afterwards, so Android behaviour must stay intact.

Replace Android-only constructs with the cross-platform helpers of the base page class. These are the only
helper methods you may call on `this` besides methods that already exist in the class:
{{helpers}}

Guidance:
- `UiScrollable`/`UiSelector` scrolling becomes `scrollToElementByText(text)` or `scrollInsideContainer(...)`.
- `AndroidKey.BACK` / pressing the system back key becomes `navigateBack()`.
- `hideKeyboard()` becomes `safeHideKeyboard()`; `openNotifications()` becomes `openSystemNotifications()`.
- A spinner or loading state that the screen XML shows needs `waitForLoadingToDisappear()` before the next action.
- Text read from elements that may differ between platforms goes through `getCleanText(element)`.
- Locators built inline (`By.id(...)`, `AppiumBy...`) cannot carry an iOS locator: replace them with an existing
  annotated field of the class when one fits, otherwise keep the lookup but make it platform-neutral
  (accessibility id) when the screen XML shows the element has one.
- Elements marked `offscreen="true"` in the screen XML need a scroll helper call before they are used.
- Do not change the method signature, the return type or the meaning of the method. Do not invent methods.
Return the complete new method body (the code between the braces, braces optional) and the imports it needs
beyond the existing ones as fully qualified class names. Answer with JSON only.
