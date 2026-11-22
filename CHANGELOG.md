# Data Warner changelog

## v1.9.2

- Replace the previous alarm when rescheduling summaries and support more document providers when exporting.
- Refresh localized dates and percentages, and format alert limits using the device locale.
- Preserve scroll position across rotation and refresh the widget when its theme changes.
- Clarify how to turn off plan tracking and expand export, notification schedule, and storage tests.

## v1.9.1

- Fix a startup crash on Android 11 when initializing the appearance of the system bars.

## v1.9

- Run Java regression checks with JUnit, with additional coverage for data plans, usage formatting, and notification timing.
- Add device tests for SQLite session migration and storage operations.
- Add GitHub Actions checks and the MIT license.

## v1.8

- Add a positive or negative data plan adjustment that expires at the next renewal.
- Let users choose usage summary frequency or a daily time and open system battery settings.
- Add light and dark widget styling and a manual refresh action.
- Add About, an in-app privacy policy, and CSV/JSON export for measurement history and usage summaries.

## v1.7

- Harden notification channels, zero-capacity plans, and measurement recovery after clock changes or interruption.
- Support the Android back gesture at the main screen and keep the scroll position when refreshing the same page.
- Separate meter, chart, settings, and shared controls from the main activity.
- Add English and Turkish resources and use the device locale for dates and usage values.

## v1.6

- Target Android 16 (API 36) and respect system bars, screen cutouts, and the keyboard.
- Limit package visibility to apps with a launcher entry.
- Add an exact-alarm settings shortcut and configure optimized Android App Bundle builds with environment-based signing.

## Changes from v1.4 to v1.5

This release collects the changes since v1.4. The signed APK supports Android 11 and later and can update v1.4 through v1.4.4 without uninstalling the app.

## v1.5

- Tapping a main tab slides both pages. Opening a detail page or going back uses a shorter slide and fade.
- App details, chart filters, and meter history prepare data before replacing the current page.
- Back, close, edit, reset, history, and delete actions use icons with accessible names. Main tab and meter controls keep their text labels.

## v1.4.4

- Restored launchable preinstalled apps such as YouTube and Chrome to usage lists.
- Made the adjacent tab follow the finger during a swipe. Main tab data is prepared in advance.
- Added meter reset without deleting past measurements. Resetting during a measurement saves it to history.
- Moved meter history to its own page, with individual and bulk deletion.

## v1.4.3

- Applied the app filter to the Apps tab, meter breakdown, and usage alert details.
- Added left and right swipe navigation between the four main tabs.
- Split the side menu into Notifications, Appearance, and Mobile Data Plan screens. The meter remains in the bottom navigation.

## v1.4.2

- Hid system apps from the Apps tab and limited long names to two lines in the list. Full names remain visible in app details.
- Smoothed tab changes, side menu motion, and button feedback. Added a subtle stretch at scroll boundaries.

## v1.4.1

- Combined usage totals, charts, and date controls on the Overview and Hotspot screens.
- Added bottom navigation icons and simplified light and dark themes.
- Added each app's share of recorded app traffic to usage lists.
- Put elapsed time, total usage, and estimated hourly usage together in the meter.

## v1.4

- Added data plan tracking with remaining allowance, renewal date, a daily budget, and alerts at 80% and 95%.
- Added named meter sessions, session history, and an hourly usage estimate.
- Added a usage breakdown when opening a data alert and a home-screen widget for today's use and the remaining plan allowance.
