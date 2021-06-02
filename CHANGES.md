Changes in Element 1.1.8 (2021-05-25)
===================================================

Improvements 🙌:
 - Support Jitsi authentication (#3379)

Bugfix 🐛:
 - Space Invite by link not always displayed for public space (#3345)
 - Wrong copy in share space bottom sheet (#3346)
 - Fix a problem with database migration on nightly builds (#3335)
 - Implement a workaround to render &lt;del&gt; and &lt;u&gt; in the timeline (#1817)
 - Make sure the SDK can retrieve the secret storage if the system is upgraded (#3304)
 - Spaces | Explore room list: the RoomId is displayed instead of name (#3371)
 - Spaces | Personal spaces add DM - Web Parity (#3271)
 - Spaces | Improve 'Leave Space' UX/UI (#3359)
 - Don't create private spaces with encryption enabled (#3363)
 - #+ button on lower right when looking at an empty space goes to an empty 'Explore rooms' (#3327)

Build 🧱:
 - Compile with Kotlin 1.5.10.
 - Upgrade some dependencies: gradle wrapper, third party lib, etc.
 - Sign APK with build tools 30.0.3

Other changes:
 - Add documentation on LoginWizard and RegistrationWizard (#3303)
 - Setup towncrier tool (#3293)

Changes in Element 1.1.7 (2021-05-12)
===================================================

Features ✨:
 - Allow changing nick colors (#2610)
 - Spaces beta

Improvements 🙌:
 - Add ability to install APK from directly from Element (#2381)
 - Delete and react to stickers (#3250)
 - Compress video before sending (#442)
 - Improve file too big error detection (#3245)
 - User can now select video when selecting Gallery to send attachments to a room
 - Add option to record a video from the camera
 - Add the public icon on the rooms in the room list (#3292)

Bugfix 🐛:
 - Message states cosmetic changes (#3007)
 - Fix exception in rxSingle (#3180)
 - Do not invite the current user when creating a room (#3123)
 - Fix color issues when the system theme is changed (#2738)
 - Fix issues on Android 11 (#3067)
 - Fix issue when opening encrypted files (#3186)
 - Fix wording issue (#3242)
 - Fix missing sender information after edits (#3184)
 - Fix read marker not updating automatically (#3267)
 - Sent video does not contains duration (#3272)
 - Properly clean the back stack if the user cancel registration when waiting for email validation
 - Fix read marker visibility/position when filtering some events 
 - Fix user invitation in case of restricted profile api (#3306)

SDK API changes ⚠️:
 - RegistrationWizard.createAccount() parameters are now all optional, following Matrix spec (#3205)

Build 🧱:
 - Upgrade to gradle 7
 - https://github.com/Piasy/BigImageViewer is now hosted on mavenCentral()
 - Upgrade Realm to version 10.4.0

Other changes:
 - New store descriptions
 - `master` branch has been renamed to `main`. To apply change to your dev environment, run:
```sh
git branch -m master main
git fetch origin
git branch -u origin/main main
# And optionally
git remote prune origin
```
 - Allow cleartext (non-SSL) connections to Matrix servers on LAN hosts (#3166)

Changes in Element 1.1.6 (2021-04-16)
===================================================

Bugfix 🐛:
 - Fix crash on the timeline
 - App crashes on "troubleshoot notifications" button (#3187)

Changes in Element 1.1.5 (2021-04-15)
===================================================

Bugfix 🐛:
 - Fix crash during Realm migration
 - Fix crash when playing video (#3179)

Changes in Element 1.1.4 (2021-04-09)
===================================================

Improvements 🙌:
 - Split network request `/keys/query` into smaller requests (250 users max) (#2925)
 - Crypto improvement | Bulk send NO_OLM withheld code
 - Display the room shield in all room setting screens
 - Improve message with Emoji only detection (#3017)
 - Picture preview when replying. Also add the image preview in the message detail bottomsheet (#2916)
 - Api interceptor to allow app developers peek responses (#2986)
 - Update reactions to Unicode 13.1 (#2998)
 - Be more robust when parsing some enums
 - Improve timeline filtering (dissociate membership and profile events, display hidden events when highlighted, fix hidden item/read receipts behavior)
 - Add better support for empty room name fallback (#3106)
 - Room list improvements (paging)
 - Fix quick click action (#3127)
 - Get Event after a Push for a faster notification display in some conditions
 - Always try to retry Http requests in case of 429 (#1300)
 - registration availability endpoint added to matrix-sdk

Bugfix 🐛:
 - Fix bad theme change for the MainActivity
 - Handle encrypted reactions (#2509)
 - Disable URL preview for some domains (#2995)
 - Fix avatar rendering for DMs, after initial sync (#2693)
 - Fix mandatory parameter in API (#3065)
 - If signout request fails, do not start LoginActivity, but restart the app (#3099)
 - Retain keyword order in emoji import script, and update the generated file (#3147)

SDK API changes ⚠️:
 - Several Services have been migrated to coroutines (#2449)
 - Removes filtering options on Timeline.

Build 🧱:
 - Properly exclude gms dependencies in fdroid build flavour which were pulled in through the jitsi SDK (#3125)

Other changes:
 - Add version details on the login screen, in debug or developer mode
 - Migrate Retrofit interface to coroutine calls

Changes in Element 1.1.3 (2021-03-18)
===================================================

Bugfix 🐛:
 - Fix regression in UpdateTrustWorker (introduced in 1.1.2)
 - Timeline : Fix ripple effect on text item and fix background color of separators.

Changes in Element 1.1.2 (2021-03-16) (was not published tp GPlay prod)
===================================================

Improvements 🙌:
 - Lazy storage of ReadReceipts
 - Do not load room members in e2e after init sync

Bugfix 🐛:
 - Add option to cancel stuck messages at bottom of timeline see #516
 - Ensure message are decrypted in the room list after a clear cache
 - Regression: Video will not play upon tap, but only after swipe #2928
 - Cross signing now works with servers with an explicit port in the servername

Other changes:
 - Change formatting on issue templates to proper headings.

Changes in Element 1.1.1 (2021-03-10) (was not published tp GPlay prod)
===================================================

Improvements 🙌:
 - Allow non-HTTPS connections to homeservers on Tor (#2941)
 - Fetch homeserver type and version and display in a new setting screen and add info in rageshakes (#2831)
 - Improve initial sync performance - split into 2 transactions (#983)
 - PIP support for Jitsi call (#2418)
 - Add tooltip for room quick actions
 - Pre-share session keys when opening a room or start typing (#2771)
 - Sending is now queuing by room and not uniquely to the session
 - Improve Snackbar duration (#2929)
 - Improve sending message state (#2937)

Bugfix 🐛:
 - Try to fix crash about UrlPreview (#2640)
 - Be robust if Event.type is missing (#2946)
 - Snappier message send status
 - Fix MainActivity display (#2927)

Translations 🗣:
 - All string resources and translations have been moved to the application module. Weblate project for the SDK will be removed.

Build 🧱:
 - Update a lot of dependencies, with the help of dependabot.
 - Add a script to download and install APK from the CI

Other changes:
 - Rework edition of event management

Changes in Element 1.1.0 (2021-02-19)
===================================================

Features ✨:
 - VoIP : support for VoIP V1 protocol, transfer call and dial-pad
                                
Improvements 🙌:
 - VoIP : new tiles in timeline
 - Improve room profile UX
 - Upgrade Jitsi library from 2.9.3 to 3.1.0
 - a11y improvements

Bugfix 🐛:
 - VoIP : fix audio devices output
 - Fix crash after initial sync on Dendrite
 - Fix crash reported by PlayStore (#2707)
 - Ignore url override from credential if it is not valid (#2822)
 - Fix crash when deactivating an account

SDK API changes ⚠️:
 - Migrate AuthenticationService API to coroutines (#2449)

Other changes:
 - New Dev Tools panel for developers
 - Fix typos in CHANGES.md (#2811)
 - Colors rework: first step: merge file `colors_riot.xml` to file `colors_riotx.xml` and rename the file to `colors.xml`

Changes in Element 1.0.17 (2021-02-09)
===================================================

Improvements 🙌:
 - Create a WidgetItemFactory and use it for better rendering of Jitsi widget change (video conference)
 - Open image from URL Preview (#2705)

Bugfix 🐛:
 - Bug in WidgetContent.computeURL() (#2767)
 - Duplicate thumbs | Mobile reactions for 👍 and 👎 are not the same as web (#2776)
 - Join room by alias other federation error (#2778)
 - HTML unescaping for URL preview (#2766)
 - URL preview on reply fallback (#2756)
 - RTL: some arrows should be rotated in RTL (#2757)
 - Properly delete objects from Realm DB (#2765)

Build 🧱:
 - Upgrade build tools

Other changes:
 - Change app name from "Element (Riot.im)" to "Element"

Changes in Element 1.0.16 (2021-02-04)
===================================================

Bugfix 🐛:
 - Fix crash on API < 30 and light theme (#2774)

Changes in Element 1.0.15 (2021-02-03)
===================================================

Features ✨:
 - Social Login support

Improvements 🙌:
 - SSO support for cross signing (#1062)
 - Deactivate account when logged in with SSO (#1264)
 - SSO UIA doesn't work (#2754)

Bugfix 🐛:
 - Fix clear cache issue: sometimes, after a clear cache, there is still a token, so the init sync service is not started.
 - Sidebar too large in horizontal orientation or tablets (#475)
 - UrlPreview should be updated when the url is edited and changed (#2678)
 - When receiving a new pepper from identity server, use it on the next hash lookup (#2708)
 - Crashes reported by PlayStore (new in 1.0.14) (#2707)
 - Widgets: Support $matrix_widget_id parameter (#2748)
 - Data for Worker overload (#2721)
 - Fix multiple tasks
 - Object deletion in database is not complete (#2759)

SDK API changes ⚠️:
 - Increase targetSdkVersion to 30 (#2600)

Build 🧱:
 - Compile with Android SDK 30 (Android 11)

Other changes:
 - Update Dagger to 2.31 version so we can use the embedded AssistedInject feature 

Changes in Element 1.0.14 (2021-01-15)
===================================================

Features ✨:
 - Enable url previews for notices (#2562)
 - Edit room permissions (#2471)

Improvements 🙌:
 - Add System theme option and set as default (#904, #2387)
 - Store megolm outbound session to improve send time of first message after app launch.
 - Warn user when they are leaving a not public room (#1460)
 - Option to disable emoji keyboard (#2563)

Bugfix 🐛:
 - Unspecced msgType field in m.sticker (#2580)
 - Wait for all room members to be known before sending a message to a e2e room (#2518)
 - Url previews sometimes attached to wrong message (#2561)
 - Room Topic not displayed correctly after visiting a link (#2551)
 - Hiding membership events works the exact opposite (#2603)
 - Tapping drawer having more than 1 room in notifications gives "malformed link" error (#2605)
 - Sent image not displayed when opened immediately after sending (#409)
 - Initial sync is not retried correctly when there is some network error. (#2632)
 - Fix switch theme issue, and white field issue (#2599, #2528)
 - Fix request too large Uri error when joining a room

Translations 🗣:
 - New language supported: Hebrew

Build 🧱:
 - Remove dependency to org.greenrobot.eventbus library

Other changes:
 - Migrate to ViewBindings (#1072)

Changes in Element 1.0.13 (2020-12-18)
===================================================

Bugfix 🐛:
 - Fix MSC2858 implementation details (#2540)

Changes in Element 1.0.12 (2020-12-15)
===================================================

Features ✨:
 - Add room aliases management, and room directory visibility management in a dedicated screen (#1579, #2428)
 - Room setting: update join rules and guest access (#2442)
 - Url preview (#481)
 - Store encrypted file in cache and cleanup decrypted file at each app start (#2512)
 - Emoji Keyboard (#2520)
 - Social login (#2452)
 - Support for chat effects in timeline (confetti, snow) (#2535)

Improvements 🙌:
 - Add Setting Item to Change PIN (#2462)
 - Improve room history visibility setting UX (#1579)
 - Matrix.to deeplink custom scheme support
 - Homeserver history (#1933)

Bugfix 🐛:
 - Fix cancellation of sending event (#2438)
 - Double bottomsheet effect after verify with passphrase
 - EditText cursor jumps to the start while typing fast (#2469)
 - UTD for events before invitation if member state events are hidden (#2486)
 - No known servers error is given when joining rooms on new Gitter bridge (#2516)
 - Show preview when sending attachment from the keyboard (#2440)
 - Do not compress GIFs (#1616, #1254)

SDK API changes ⚠️:
 - StateService now exposes suspendable function instead of using MatrixCallback.
 - RawCacheStrategy has been moved and renamed to CacheStrategy
 - FileService: remove useless FileService.DownloadMode

Build 🧱:
 - Upgrade some dependencies and Kotlin version
 - Use fragment-ktx and preference-ktx dependencies (fix lint issue KtxExtensionAvailable)
 - Upgrade Realm dependency to 10.1.2

Other changes:
 - Remove "Status.im" theme #2424
 - Log HTTP requests and responses in production (level BASIC, i.e. without any private data)

Changes in Element 1.0.11 (2020-11-27)
===================================================

Features ✨:
 - Create DMs with users by scanning their QR code (#2025)
 - Add Invite friends quick invite actions (#2348)
 - Add friend by scanning QR code, show your code to friends (#2025)

Improvements 🙌:
 - New room creation tile with quick action (#2346)
 - Open an existing DM instead of creating a new one (#2319)
 - Use RoomMember instead of User in the context of a Room.
 - Ask for explicit user consent to send their contact details to the identity server (#2375)
 - Handle events of type "m.room.server_acl" (#890)
 - Room creation form: add advanced section to disable federation (#1314)
 - Move "Enable Encryption" from room setting screen to room profile screen (#2394)
 - Home empty screens quick design update (#2347)
 - Improve Invite user screen (seamless search for matrix ID)

Bugfix 🐛:
 - Fix crash on AttachmentViewer (#2365)
 - Exclude yourself when decorating rooms which are direct or don't have more than 2 users (#2370)
 - F-Droid version: ensure timeout of sync request can be more than 60 seconds (#2169)
 - Fix issue when restoring draft after sharing (#2287)
 - Fix issue when updating the avatar of a room (new avatar vanishing)
 - Discard change dialog displayed by mistake when avatar has been updated
 - Try to fix cropped image in timeline (#2126)
 - Registration: annoying error message scares every new user when they add an email (#2391)
 - Fix jitsi integration for those with non-vanilla dialler frameworks
 - Update profile has no effect if user is in zero rooms
 - Fix issues with matrix.to deep linking (#2349)

SDK API changes ⚠️:
 - AccountService now exposes suspendable function instead of using MatrixCallback (#2354).
   Note: We will incrementally migrate all the SDK API in a near future (#2449)

Test:
 - Add `allScreensTest` to cover all screens of the app

Other changes:
 - Upgrade Realm dependency to 10.0.0

Changes in Element 1.0.10 (2020-11-04)
===================================================

Improvements 🙌:
 - Rework sending Event management (#154)
 - New room creation screen: set topic and avatar in the room creation form (#2078)
 - Toggle Low priority tag (#1490)
 - Add option to send with enter (#1195)
 - Use Hardware keyboard enter to send message (use shift-enter for new line) (#1881, #1440)
 - Edit and remove icons are now visible on image attachment preview screen (#2294)
 - Room profile: BigImageViewerActivity now only display the image. Use the room setting to change or delete the room Avatar
 - Better visibility of text reactions in dark theme (#1118)
 - Room member profile: Add action to create (or open) a DM (#2310)
 - Prepare changelog for F-Droid (#2296)
 - Add graphic resources for F-Droid (#812, #2220)
 - Highlight text in the body of the displayed result (#2200)
 - Considerably faster QR-code bitmap generation (#2331)

Bugfix 🐛:
 - Fixed ringtone handling (#2100 & #2246)
 - Messages encrypted with no way to decrypt after SDK update from 0.18 to 1.0.0 (#2252)
 - Incoming call continues to ring if call is answered on another device (#1921)
 - Search Result | scroll jumps after pagination (#2238)
 - Badly formatted mentions in body (#1506)
 - KeysBackup: Avoid using `!!` (#2262)
 - Two elements in the task switcher (#2299)

Changes in Element 1.0.9 (2020-10-16)
===================================================

Features ✨:
 - Search messages in a room - phase 1 (#2110)
 - Hide encrypted history (before user is invited). Can be shown if wanted in developer settings
 - Changed rainbow algorithm

Improvements 🙌:
 - Wording differentiation for direct rooms (#2176)
 - PIN code: request PIN code if phone has been locked
 - Small optimisation of scrolling experience in timeline (#2114)
 - Allow user to reset cross signing if he has no way to recover (#2052)
 - Ability to share text
 - Create home shortcut for any room (#1525)
 - Can't confirm email due to killing by Android (#2021)
 - Add a menu item to open the setting in room list and in room (#2171)
 - Add a menu item in the timeline as a shortcut to invite user (#2171)
 - Drawer: move settings access and add sign out action (#2171)
 - Filter room member (and banned users) by name (#2184)
 - Implement "Jump to read receipt" and "Mention" actions on the room member profile screen
 - Direct share (#2029)
 - Add FAB to room members list (#2226)
 - Add Sygnal API implementation to test is Push are correctly received
 - Add PushGateway API implementation to test if Push are correctly received
 - Cross signing: shouldn't offer to verify with other session when there is not. (#2227)

Bugfix 🐛:
 - Improve support for image/audio/video/file selection with intent changes (#1376)
 - Fix Splash layout on small screens
 - Invalid popup when pressing back (#1635)
 - Simplifies draft management and should fix bunch of draft issues (#952, #683)
 - Very long topic cannot be fully visible (#1957)
 - Properly detect cross signing keys reset
 - Don't set presence when handling a push notification or polling (#2156)
 - Be robust against `StrandHogg` task injection
 - Clear alerts if user sign out
 - Fix rows are hidden in Textinput (#2234)
 - Uploading a file to a room caused it to have a info.size of -1 (#2141)

Translations 🗣:
 - Move store data to `/fastlane/metadata/android` (#812)
 - Weblate is now hosted at https://translate.element.io

SDK API changes ⚠️:
 - Search messages in a room by using Session.searchService() or Room.search()

Build 🧱:
 - Use Update Gradle Wrapper Action
 - Updates Gradle Wrapper from 5.6.4 to 6.6.1. (#2193)
 - Upgrade kotlin version from `1.3.72` to `1.4.10` and kotlin coroutines version from `1.3.8` to `1.3.9`
 - Upgrade build tools from `3.5.3` to `4.0.1`, then to `4.1.0`
 - Upgrade com.google.gms:google-services from `4.3.2` to `4.3.4`
 - Upgrade Moshi to `1.11.0`, Dagger to `2.29.1`, Epoxy to `4.1.0`

Other changes:
 - Added registration/verification automated UI tests
 - Create a script to help getting public information form any homeserver

Changes in Element 1.0.8 (2020-09-25)
===================================================

Improvements 🙌:
 - Add "show password" in import Megolm keys dialog
 - Visually disable call buttons in menu and prohibit calling when permissions are insufficient (#2112)
 - Better management of requested permissions (#2048)
 - Add a setting to show timestamp for all messages (#2123)
 - Use cache for user color
 - Allow using an outdated homeserver, at user's risk (#1972)
 - Restore small logo on login screens and fix scrolling issue on those screens
 - PIN Code Improvements: Add more settings: biometrics, grace period, notification content (#1985)

Bugfix 🐛:
 - Long message cannot be sent/takes infinite time & blocks other messages (#1397)
 - Fix crash when wellknown are malformed, or redirect to some HTML content (reported by rageshakes)
 - User Verification in DM not working
 - Manual import of Megolm keys does back up the imported keys
 - Auto scrolling to the latest message when sending (#2094)
 - Fix incorrect permission check when creating widgets (#2137)
 - Pin code: user has to enter pin code twice (#2005)

SDK API changes ⚠️:
 - Rename `tryThis` to `tryOrNull`

Other changes:
 - Add an advanced action to reset an account data entry

Changes in Element 1.0.7 (2020-09-17)
===================================================

Improvements 🙌:
 - Handle date formatting properly (show time am/pm if needed, display year when needed)
 - Improve F-Droid Notification (#2055)

Bugfix 🐛:
 - Clear the notification when the event is read elsewhere (#1822)
 - Speakerphone is not used for ringback tone (#1644, #1645)
 - Back camera preview is not mirrored anymore (#1776)
 - Various report of people that cannot play video (#2107)
 - Rooms incorrectly marked as unread (#588)
 - Allow users to show/hide room member state events (#1231) 
 - Fix stuck on loader when launching home

SDK API changes ⚠️:
 - Create a new RawService to get plain data from the server.

Other changes:
 - Performance: share Realm instance used on UI thread and improve SharedPreferences reading time.

Changes in Element 1.0.6 (2020-09-08)
===================================================

Features ✨:
 - List phone numbers and emails added to the Matrix account, and add emails and phone numbers to account (#44, #45)

Improvements 🙌:
 - You can now join room through permalink and within room directory search
 - Add long click gesture to copy userId, user display name, room name, room topic and room alias (#1774)
 - Fix several issues when uploading big files (#1889)
 - Do not propose to verify session if there is only one session and 4S is not configured (#1901)
 - Call screen does not use proximity sensor (#1735)

Bugfix 🐛:
 - Display name not shown under Settings/General (#1926)
 - Editing message forgets line breaks and markdown (#1939)
 - Words containing my name should not trigger notifications (#1781)
 - Fix changing language issue
 - Fix FontSize issue (#1483, #1787)
 - Fix bad color for settings icon on Android < 24 (#1786)
 - Change user or room avatar: when selecting Gallery, I'm not proposed to crop the selected image (#1590)
 - Loudspeaker is always used (#1685)
 - Fix uploads still don't work with room v6 (#1879)
 - Can't handle ongoing call events in background (#1992)
 - Handle room, user and group links by the Element app (#1795)
 - Update associated site domain (#1833)
 - Crash / Attachment viewer: Cannot draw a recycled Bitmap #2034
 - Login with Matrix-Id | Autodiscovery fails if identity server is invalid and Homeserver ok (#2027)
 - Support for image compression on Android 10
 - Verification popup won't show
 - Android 6: App crash when read Contact permission is granted (#2064)
 - JSON for verification events leaks in to the room list (#1246)
 - Replies to poll appears in timeline as unsupported events during sending (#1004)

Translations 🗣:
 - The SDK is now using SAS string translations from [Weblate Matrix-doc project](https://translate.element.io/projects/matrix-doc/) (#1909)
 - New translation to kabyle

Build 🧱:
 - Some dependencies have been upgraded (coroutine, recyclerView, appCompat, core-ktx, firebase-messaging)
 - Buildkite:
    New pipeline location: https://github.com/matrix-org/pipelines/blob/master/element-android/pipeline.yml
    New build location: https://buildkite.com/matrix-dot-org/element-android

Other changes:
 - Use File extension functions to make code more concise (#1996)
 - Create a script to import SAS strings (#1909)
 - Support `data-mx-[bg-]color` attributes on `<font>` tags.

Changes in Element 1.0.5 (2020-08-21)
===================================================

Features ✨:
 - Protect access to the app by a pin code (#1700)
 - Conference with Jitsi support (#43)

Improvements 🙌:
 - Share button in rooms gives room ID link without via parameters (#1927)
 - Give user the possibility to prevent accidental call (#1869)
 - Display device information (name, id and key) in Cryptography setting screen (#1784)
 - Ensure users do not accidentally ignore other users (#1890)
 - Better handling DM creation when invitees cannot be inviting due to denied federation
 - Support new config.json format and config.domain.json files (#1682)
 - Increase Font size on Calling screen (#1643)
 - Make the user's Avatar live in the general settings

Bugfix 🐛:
 - Fix incorrect date format for some Asian languages (#1928)
 - Fix invisible toolbar (Status.im theme) (#1746)
 - Fix relative date time formatting (#822)
 - Fix crash reported by RageShake
 - Fix refreshing of sessions list when another session is logged out
 - Fix IllegalArgumentException: Receiver not registered: NetworkInfoReceiver (#1960)
 - Failed to build unique file (#1954)
 - Highlighted Event when opening a permalink from another room (#1033)
 - A Kick appears has "someone has made no change" (#1959)
 - Avoid NetworkOnMainThreadException when setting a user avatar
 - Renew turnserver credentials when ttl runs out

Translations 🗣:
 - Add PlayStore description resources in the Triple-T format, to let Weblate handle them

SDK API changes ⚠️:
 - Rename package `im.vector.matrix.android` to `org.matrix.android.sdk`
 - Rename package `im.vector.matrix.rx` to `org.matrix.android.sdk.rx`

Build 🧱:
 - Fix RtlHardcoded issues (use `Start` and `End` instead of `Left` and `Right` layout attributes)

Other changes:
 - Use `Context#getSystemService` extension function provided by `core-ktx` (#1702)
 - Hide Flair settings, this is not implemented yet.
 - Rename package `im.vector.riotx.attachmentviewer` to `im.vector.lib.attachmentviewer`
 - Rename package `im.vector.riotx.multipicker` to `im.vector.lib.multipicker`
 - Rename package `im.vector.riotx` to `im.vector.app`
 - Remove old code that was used on devices with api level <21
 - Add Official Gradle Wrapper Validation Action

Changes in Element 1.0.4 (2020-08-03)
===================================================

Bugfix 🐛:
 - Fix Crash when opening invite to room user screen

Changes in Element 1.0.3 (2020-07-31)
===================================================

Features ✨:
 - Support server admin option to disable E2EE for DMs / private rooms [users can still enable] (#1794)

Bugfix 🐛:
 - Crash reported on playstore for HomeActivity launch (151 reports)

Changes in Element 1.0.2 (2020-07-29)
===================================================

Improvements 🙌:
 - Added Session Database migration to avoid unneeded initial syncs

Changes in Element 1.0.1 (2020-07-28)
===================================================

Improvements 🙌:
 - Sending events is now retried only 3 times, so we avoid blocking the sending queue too long.
 - Display warning when fail to send events in room list
 - Improve UI of edit role action in member profile
 - Moderation | New screen to display list of banned users in room settings, with unban action

Bugfix 🐛:
 - Fix theme issue on Room directory screen (#1613)
 - Fix notification not dismissing when entering a room
 - Fix uploads don't work with Room v6 (#1558)
 - Fix Requesting avatar thumbnails in Element uses wrong http "user-agent" string (#1725)
 - Fix 404 on EMS (#1761)
 - Fix Infinite loop at startup when migrating account from Riot (#1699)
 - Fix Element crashes in loop after initial sync (#1709)
 - Remove inner mx-reply tags before replying
 - Fix timeline items not loading when there are only filtered events
 - Fix "Voice & Video" grayed out in Settings (#1733)
 - Fix Allow VOIP call in all rooms with 2 participants (even if not DM)
 - Migration from old client does not enable notifications (#1723)

Other changes:
 - i18n deactivated account error

Changes in Element 1.0.0 (2020-07-15)
===================================================

Features ✨:
 - Re-branding: The app is now called Element. New name, new themes, new icons, etc. More details here: https://element.io/blog/welcome-to-element/ (#1691)

Bugfix 🐛:
 - Video calls are shown as a voice ones in the timeline (#1676)
 - Fix regression: not able to create a room without IS configured (#1679)

Changes in Riot.imX 0.91.5 (2020-07-11)
===================================================

Features ✨:
 - 3pid invite: it is now possible to invite people by email. An Identity Server has to be configured (#548)

Improvements 🙌:
 - Cleaning chunks with lots of events as long as a threshold has been exceeded (35_000 events in DB) (#1634)
 - Creating and listening to EventInsertEntity. (#1634)
 - Handling (almost) properly the groups fetching (#1634)
 - Improve fullscreen media display (#327)
 - Setup server recovery banner (#1648)
 - Set up SSSS from security settings (#1567)
 - New lab setting to add 'unread notifications' tab to main screen
 - Render third party invite event (#548)
 - Display three pid invites in the room members list (#548)

Bugfix 🐛:
 - Integration Manager: Wrong URL to review terms if URL in config contains path (#1606)
 - Regression Composer does not grow, crops out text (#1650)
 - Bug / Unwanted draft (#698)
 - All users seems to be able to see the enable encryption option in room settings (#1341)
 - Leave room only leaves the current version (#1656)
 - Regression |  Share action menu do not work (#1647)
 - verification issues on transition (#1555)
 - Fix issue when restoring keys backup using recovery key

SDK API changes ⚠️:
 - CreateRoomParams has been updated

Build 🧱:
 - Upgrade some dependencies
 - Revert to build-tools 3.5.3

Other changes:
 - Use Intent.ACTION_CREATE_DOCUMENT to save megolm key or recovery key in a txt file
 - Use `Context#withStyledAttributes` extension function (#1546)

Changes in Riot.imX 0.91.4 (2020-07-06)
===================================================

Features ✨:
 - Re-activate Wellknown support with updated UI (#1614)

Improvements 🙌:
 - Upload device keys only once to the homeserver and fix crash when no network (#1629)

Bugfix 🐛:
 - Fix crash when coming from a notification (#1601)
 - Fix Exception when importing keys (#1576)
 - File isn't downloaded when another file with the same name already exists (#1578)
 - saved images don't show up in gallery (#1324)
 - Fix reply fallback leaking sender locale (#429)

Build 🧱:
 - Fix lint false-positive about WorkManager (#1012)
 - Upgrade build-tools from 3.5.3 to 3.6.3
 - Upgrade gradle from 5.4.1 to 5.6.4

Changes in Riot.imX 0.91.3 (2020-07-01)
===================================================

Notes:
 - This version is the third beta version of RiotX codebase published as Riot-Android on the PlayStore.
 - Changelog below includes changes of v0.91.0, v0.91.1, and v0.91.2, because the first beta versions have been tagged and
 published from the branch feature/migration_from_legacy.
 - This version uses temporary name `Riot.imX`, to distinguish the app with RiotX app.

Features ✨:
 - Call with WebRTC support (##611)
 - Add capability to change the display name (#1529)

Improvements 🙌:
 - "Add Matrix app" menu is now always visible (#1495)
 - Handle `/op`, `/deop`, and `/nick` commands (#12)
 - Prioritising Recovery key over Recovery passphrase (#1463)
 - Room Settings: Name, Topic, Photo, Aliases, History Visibility (#1455)
 - Update user avatar (#1054)
 - Allow self-signed certificate (#1564)
 - Improve file download and open in timeline
 - Catchup tab is removed temporarily (#1565)
 - Render room avatar change (#1319)

Bugfix 🐛:
 - Fix dark theme issue on login screen (#1097)
 - Incomplete predicate in RealmCryptoStore#getOutgoingRoomKeyRequest (#1519)
 - User could not redact message that they have sent (#1543)
 - Use vendor prefix for non merged MSC (#1537)
 - Compress images before sending (#1333)
 - Searching by displayname is case sensitive (#1468)
 - Fix layout overlap issue (#1407)

Build 🧱:
 - Enable code optimization (Proguard)
 - SDK is now API level 21 minimum, and so RiotX (#405)

Other changes:
 - Use `SharedPreferences#edit` extension function consistently (#1545)
 - Use `retrofit2.Call.awaitResponse` extension provided by Retrofit 2. (#1526)
 - Fix minor typo in contribution guide (#1512)
 - Fix self-assignment of callback in `DefaultRoomPushRuleService#setRoomNotificationState` (#1520)
 - Random housekeeping clean-ups indicated by Lint (#1520, #1541)
 - Keys Backup API now use the unstable prefix (#1503)
 - Remove deviceId from /keys/upload/{deviceId} as not spec-compliant (#1502)

Changes in RiotX 0.22.0 (2020-06-15)
===================================================

Features ✨:
 - Integration Manager and Widget support (#48)
 - Send stickers (#51)

Improvements 🙌:
 - New wording for notice when current user is the sender
 - Hide "X made no changes" event by default in timeline (#1430)
 - Hide left rooms in breadcrumbs (#766)
 - Handle PowerLevel properly (#627)
 - Correctly handle SSO login redirection
 - SSO login is now performed in the default browser, or in Chrome Custom tab if available (#1400)
 - Improve checking of homeserver version support (#1442)
 - Add capability to add and remove a room from the favorites (#1217)

Bugfix 🐛:
 - Switch theme is not fully taken into account without restarting the app
 - Temporary fix to show error when user is creating an account on matrix.org with userId containing only digits (#1410)
 - Reply composer overlay stays on screen too long after send (#1169)
 - Fix navigation bar icon contrast on API in [21,27[ (#1342)
 - Fix status bar icon contrast on API in [21,23[
 - Wrong /query request (#1444)
 - Make Credentials.homeServer optional because it is deprecated (#1443)
 - Fix issue on dark themes, after alert popup dismiss

Other changes:
 - Send plain text in the body of events containing formatted body, as per https://matrix.org/docs/spec/client_server/latest#m-room-message-msgtypes
 - Update link to Modular url from "https://modular.im/" to "https://modular.im/services/matrix-hosting-riot" and open it using ChromeCustomTab

Changes in RiotX 0.21.0 (2020-05-28)
===================================================

Features ✨:
 - Identity server support (#607)
 - Switch language support (#41)
 - Display list of attachments of a room (#860)

Improvements 🙌:
 - Better connectivity lost indicator when airplane mode is on
 - Add a setting to hide redacted events (#951)
 - Render formatted_body for m.notice and m.emote (#1196)
 - Change icon to magnifying-glass to filter room (#1384)

Bugfix 🐛:
 - After jump to unread, newer messages are never loaded (#1008)
 - Fix issues with FontScale switch (#69, #645)
 - "Seen by" uses 12h time (#1378)
 - Enable markdown (if active) when sending emote (#734)
 - Screenshots for Rageshake now includes Dialogs such as BottomSheet (#1349)

SDK API changes ⚠️:
 - initialize with proxy configuration

Other changes:
 - support new key agreement method for SAS (#1374)

Changes in RiotX 0.20.0 (2020-05-15)
===================================================

Features ✨:
 - Add Direct Shortcuts (#652)

Improvements 🙌:
 - Invite member(s) to an existing room (#1276)
 - Improve notification accessibility with ticker text (#1226)
 - Support homeserver discovery from MXID (DISABLED: waiting for design) (#476)

Bugfix 🐛:
 - Fix | Verify Manually by Text crashes if private SSK not known (#1337)
 - Sometimes the same device appears twice in the list of devices of a user (#1329)
 - Random Crashes while doing sth with cross signing keys (#1364)
 - Crash | crash while restoring key backup (#1366)

SDK API changes ⚠️:
 - excludedUserIds parameter added to the UserService.getPagedUsersLive() function

Changes in RiotX 0.19.0 (2020-05-04)
===================================================

Features ✨:
 - Change password (#528)
 - Cross-Signing | Support SSSS secret sharing (#944)
 - Cross-Signing | Verify new session from existing session (#1134)
 - Cross-Signing | Bootstraping cross signing with 4S from mobile (#985)
 - Save media files to Gallery (#973)
 - Account deactivation (with password only) (#35)

Improvements 🙌:
 - Verification DM / Handle concurrent .start after .ready (#794)
 - Reimplementation of multiple attachment picker
 - Cross-Signing | Update Shield Logic for DM (#963)
 - Cross-Signing | Complete security new session design update (#1135)
 - Cross-Signing | Setup key backup as part of SSSS bootstrapping (#1201)
 - Cross-Signing | Gossip key backup recovery key (#1200)
 - Show room encryption status as a bubble tile (#1078)
 - UX/UI | Add indicator to home tab on invite (#957)
 - Cross-Signing | Restore history after recover from passphrase (#1214)
 - Cross-Sign | QR code scan confirmation screens design update (#1187)
 - Emoji Verification | It's not the same butterfly! (#1220)
 - Cross-Signing | Composer decoration: shields (#1077)
 - Cross-Signing | Migrate existing keybackup to cross signing with 4S from mobile (#1197)
 - Show a warning dialog if the text of the clicked link does not match the link target (#922)
 - Cross-Signing | Consider not using a spinner on the 'complete security' prompt (#1271)
 - Restart broken Olm sessions ([MSC1719](https://github.com/matrix-org/matrix-doc/pull/1719))
 - Cross-Signing | Hide Use recovery key when 4S is not setup (#1007)
 - Cross-Signing | Trust account xSigning keys by entering Recovery Key (select file or copy) #1199
 - E2E timeline decoration (#1279)
 - Manage Session Settings / Cross Signing update (#1295)
 - Cross-Signing | Review sessions toast update old vs new (#1293, #1306)

Bugfix 🐛:
 - Fix summary notification staying after "mark as read"
 - Missing avatar/displayname after verification request message (#841)
 - Crypto | RiotX sometimes rotate the current device keys (#1170)
 - RiotX can't restore cross signing keys saved by web in SSSS (#1174)
 - Cross- Signing | After signin in new session, verification paper trail in DM is off (#1191)
 - Failed to encrypt message in room (message stays in red), [thanks to pwr22] (#925)
 - Cross-Signing | web <-> riotX After QR code scan, gossiping fails (#1210)
 - Fix crash when trying to download file without internet connection (#1229)
 - Local echo are not updated in timeline (for failed & encrypted states)
 - Render image event even if thumbnail_info does not have mimetype defined (#1209)
 - RiotX now uses as many threads as it needs to do work and send messages (#1221)
 - Fix issue with media path (#1227)
 - Add user to direct chat by user id (#1065)
 - Use correct URL for SSO connection (#1178)
 - Emoji completion :tada: does not completes to 🎉 like on web (#1285)
 - Fix bad Shield Logic for DM (#963)

Translations 🗣:
 - Weblate now create PR directly to RiotX GitHub project

SDK API changes ⚠️:
 - Increase targetSdkVersion to 29

Build 🧱:
 - Compile with Android SDK 29 (Android Q)

Other changes:
 - Add a setting to prevent screenshots of the application, disabled by default (#1027)
 - Increase File Logger capacities ( + use dev log preferences)

Changes in RiotX 0.18.1 (2020-03-17)
===================================================

Improvements 🙌:
 - Implementation of /join command

Bugfix 🐛:
 - Message transitions in encrypted rooms are jarring #518
 - Images that failed to send are waiting to be sent forever #1145
 - Fix / Crashed when trying to send a gif from the Gboard #1136
 - Fix / Cannot click on key backup banner when new keys are available


Changes in RiotX 0.18.0 (2020-03-11)
===================================================

Improvements 🙌:
 - Share image and other media from e2e rooms (#677)
 - Add support for `/plain` command (#12)
 - Detect spaces in password if user fail to login (#1038)
 - FTUE: do not display a different color when encrypting message when not in developer mode.
 - Open room member profile from avatar of the room member state event (#935)
 - Restore the push rules configuration in the settings

Bugfix 🐛:
 - Fix crash on attachment preview screen (#1088)
 - "Share" option is not appearing in encrypted rooms for images (#1031)
 - Set "image/jpeg" as MIME type of images instead of "image/jpg" (#1075)
 - Self verification via QR code is failing (#1130)

SDK API changes ⚠️:
 - PushRuleService.getPushRules() now returns a RuleSet. Use getAllRules() on this object to get all the rules.

Build 🧱:
 - Upgrade ktlint to version 0.36.0
 - Pipeline file for Buildkite is now hosted on another Github repository: https://github.com/matrix-org/pipelines/blob/master/riotx-android/pipeline.yml

Other changes:
 - Restore availability to Chromebooks (#932)
 - Add a [documentation](./docs/integration_tests.md) to run integration tests

Changes in RiotX 0.17.0 (2020-02-27)
===================================================

Features ✨:
 - Secured Shared Storage Support (#984, #936)
 - It's now possible to select several rooms (with a possible mix of clear/encrypted rooms) when sharing elements to RiotX (#1010)
 - Media preview: media are previewed before being sent to a room (#1010)
 - Image edition: it's now possible to edit image before sending: crop, rotate, and delete actions are supported (#1010)
 - Sending image: image are sent to rooms with a reduced size. It's still possible to send original image file (#1010)

Improvements 🙌:
 - Migrate to binary QR code verification (#994)
 - Share action is added to room profile and room member profile (#858)
 - Display avatar in fullscreen (#861)
 - Fix some performance issues with crypto

Bugfix 🐛:
 - Account creation: wrongly hints that an email can be used to create an account (#941)
 - Fix crash in the room directory, when public room has no name (#1023)
 - Fix restoring keys backup with passphrase (#526)
 - Fix rotation of full-size image (#647)
 - Fix joining rooms from directory via federation isn't working. (#808)
 - Leaving a room creates a stuck "leaving room" loading screen. (#1041)
 - Fix some invitation handling issues (#1013)
 - New direct chat: selecting a participant sometimes results in two breadcrumbs (#1022)
 - New direct chat: selecting several participants was not adding the room to the direct chats list
 - Room overview shows deleted messages as “Encrypted message” (#758)

SDK API changes ⚠️:
 - Get crypto methods through Session.cryptoService()
 - ProgressListener.onProgress() function will be invoked on the background thread instead of UI thread
 - Improve CreateRoomParams API (#1070)

Changes in RiotX 0.16.0 (2020-02-14)
===================================================

Features ✨:
 - Polls and Bot Buttons (MSC 2192 matrix-org/matrix-doc#2192)

Improvements 🙌:
 - Show confirmation dialog before deleting a message (#967, #1003)
 - Open room member profile from reactions list and read receipts list (#875)

Bugfix 🐛:
 - Fix crash by removing all notifications after clearing cache (#878)
 - Fix issue with verification when other client declares it can only show QR code (#988)
 - Fix too errors in the code (1941862499c9ec5268cc80882512ced379cafcfd, a250a895fe0a4acf08c671e03434edcd29ccd84f)

SDK API changes ⚠️:
 - Javadoc improved for PushersService
 - PushersService.pushers() has been renamed to PushersService.getPushers()

Changes in RiotX 0.15.0 (2020-02-10)
===================================================

Improvements 🙌:
 - Improve navigation to the timeline (#789, #862)
 - Improve network detection. It is now based on the sync request status (#873, #882)

Other changes:
 - Support SSO login with Firefox account (#606)

Bugfix 🐛:
 - Ask for permission before opening the camera (#934)
 - Encrypt for invited users by default, if the room state allows it (#803)

Changes in RiotX 0.14.3 (2020-02-03)
===================================================

Bugfix 🐛:
 - Fix Exception in DeviceListManager

Changes in RiotX 0.14.2 (2020-02-02)
===================================================

Bugfix 🐛:
 - Fix RiotX not starting issue

Changes in RiotX 0.14.1 (2020-02-02)
===================================================

Bugfix 🐛:
 - Cross-signing: fix UX issue when closing the bottom sheet verification (#813)
 - Room and room member profile: fix issues on dark and black themes

Changes in RiotX 0.14.0 (2020-02-01)
===================================================

Features ✨:
 - First implementation of Cross-signing
 - Enable encryption in unencrypted rooms, from the room settings (#212)
 - Negotiate E2E by default for DMs (#907)

Improvements 🙌:
 - Sharing things to RiotX: sort list by recent room first (#771)
 - Hide the algorithm when turning on e2e (#897)
 - Sort room members by display names

Other changes:
 - Add support for /rainbow and /rainbowme commands (#879)

Build 🧱:
 - Ensure builds are reproducible (#842)
 - F-Droid: fix the "-dev" issue in version name (#815)

Changes in RiotX 0.13.0 (2020-01-17)
===================================================

Features ✨:
 - Send and render typing events (#564)
 - Create Room Profile screen (#54)
 - Create Room Member Profile screen (#59)

Improvements 🙌:
 - Render events m.room.encryption and m.room.guest_access in the timeline

Bugfix 🐛:
 - Fix broken background sync in F-Droid version
 - Fix issue with downloaded file on encrypted rooms. The file was not properly decrypted

Build 🧱:
 - Change the way versionCode is computed (#827)

Changes in RiotX 0.12.0 (2020-01-09)
===================================================

Improvements 🙌:
 - The initial sync is now handled by a foreground service
 - Render aliases and canonical alias change in the timeline
 - Introduce developer mode in the settings (#745, #796)
 - Improve devices list screen
 - Add settings for rageshake sensibility
 - Fix autocompletion issues and add support for rooms, groups, and emoji (#780)
 - Show skip to bottom FAB while scrolling down (#752)
 - Enable encryption on a room, SDK part (#212)

Other changes:
 - Change the way RiotX identifies a session to allow the SDK to support several sessions with the same user (#800)
 - Exclude play-services-oss-licenses library from F-Droid build (#814)
 - Email domain can be limited on some homeservers, i18n of the displayed error (#754)

Bugfix 🐛:
 - Fix crash when opening room creation screen from the room filtering screen
 - Fix avatar image disappearing (#777)
 - Fix read marker banner when permalink
 - Fix joining upgraded rooms (#697)
 - Fix matrix.org room directory not being browsable (#807)
 - Hide non working settings (#751)

Changes in RiotX 0.11.0 (2019-12-19)
===================================================

Features ✨:
 - Implement soft logout (#281)

Improvements 🙌:
 - Handle navigation to room via room alias (#201)
 - Open matrix.to link in RiotX (#57)
 - Limit sticker size in the timeline

Other changes:
 - Use same default room colors than Riot-Web

Bugfix 🐛:
 - Scroll breadcrumbs to top when opened
 - Render default room name when it starts with an emoji (#477)
 - Do not display " (IRC)" in display names https://github.com/vector-im/riot-android/issues/444
 - Fix rendering issue with HTML formatted body
 - Disable click on Stickers (#703)

Build 🧱:
 - Include diff-match-patch sources as dependency

Changes in RiotX 0.10.0 (2019-12-10)
===================================================

Features ✨:
 - Breadcrumbs: switch from one room to another quickly (#571)

Improvements 🙌:
 - Support entering a RiotWeb client URL instead of the homeserver URL during connection (#744)

Other changes:
 - Add reason for all membership events (https://github.com/matrix-org/matrix-doc/pull/2367)

Bugfix 🐛:
 - When automardown is ON, pills are sent as MD in body (#739)
 - "ban" event are not rendered correctly (#716)
 - Fix crash when rotating screen in Room timeline

Changes in RiotX 0.9.1 (2019-12-05)
===================================================

Bugfix 🐛:
 - Fix an issue with DB transaction (#740)

Changes in RiotX 0.9.0 (2019-12-05)
===================================================

Features ✨:
 - Account creation. It's now possible to create account on any homeserver with RiotX (#34)
 - Iteration of the login flow (#613)
 - [SDK] MSC2241 / verification in DMs (#707)

Improvements 🙌:
 - Send mention Pills from composer
 - Links in message preview in the bottom sheet are now active. 
 - Rework the read marker to make it more usable

Other changes:
 - Fix a small grammatical error when an empty room list is shown.

Bugfix 🐛:
 - Do not show long click help if only invitation are displayed
 - Fix emoji filtering not working
 - Fix issue of closing Realm in another thread (#725)
 - Attempt to properly cancel the crypto module when user signs out (#724)

Changes in RiotX 0.8.0 (2019-11-19)
===================================================

Features ✨:
 - Handle long click on room in the room list (#395)
 - Ignore/UnIgnore users, and display list of ignored users (#542, #617)

Improvements 🙌:
 - Search reaction by name or keyword in emoji picker
 - Handle code tags (#567)
 - Support spoiler messages
 - Support m.sticker and m.room.join_rules events in timeline

Other changes:
 - Markdown set to off by default (#412)
 - Accessibility improvements to the attachment file type chooser

Bugfix 🐛:
 - Fix issues with some member events rendering (#498)
 - Passphrase does not match (Export room keys) (#644)
 - Ask for permission to write external storage when uri comes from the keyboard (#658)
 - Fix issue with english US/GB translation (#671)

Changes in RiotX 0.7.0 (2019-10-24)
===================================================

Features:
 - Share elements from other app to RiotX (#58)
 - Read marker (#84)
 - Add ability to report content (#515)

Improvements:
 - Persist active tab between sessions (#503)
 - Do not upload file too big for the homeserver (#587)
 - Attachments: start using system pickers (#52)
 - Mark all messages as read (#396)


Other changes:
 - Accessibility improvements to read receipts in the room timeline and reactions emoji chooser

Bugfix:
 - Fix issue on upload error in loop (#587)
 - Fix opening a permalink: the targeted event is displayed twice (#556)
 - Fix opening a permalink paginates all the history up to the last event (#282)
 - after login, the icon in the top left is a green 'A' for (all communities) rather than my avatar (#267)
 - Picture uploads are unreliable, pictures are shown in wrong aspect ratio on desktop client (#517)
 - Invitation notifications are not dismissed automatically if room is joined from another client (#347)
 - Opening links from RiotX reuses browser tab (#599)

Changes in RiotX 0.6.1 (2019-09-24)
===================================================

Bugfix:
 - Fix crash: MergedHeaderItem was missing dimensionConverter

Changes in RiotX 0.6.0 (2019-09-24)
===================================================

Features:
 - Save draft of a message when exiting a room with non empty composer (#329)

Improvements:
 - Add unread indent on room list (#485)
 - Message Editing: Update notifications (#128)
 - Remove any notification of a redacted event (#563)

Other changes:
 - Fix a few accessibility issues

Bugfix:
 - Fix characters erased from the Search field when the result are coming (#545)
 - "No connection" banner was displayed by mistake
 - Leaving community (from another client) has no effect on RiotX (#497)
 - Push rules was not retrieved after a clear cache
 - m.notice messages trigger push notifications (#238)
 - Embiggen messages with multiple emojis also for edited messages (#458)

Build:
 - Fix (again) issue with bad versionCode generated by Buildkite (#553)

Changes in RiotX 0.5.0 (2019-09-17)
===================================================

Features:
 - Implementation of login to homeserver with SSO (#557)
 - Handle M_CONSENT_NOT_GIVEN error (#64)
 - Auto configure homeserver and identity server URLs of LoginActivity with a magic link

Improvements:
 - Reduce default release build log level, and lab option to enable more logs.
 - Display a no network indicator when there is no network (#559)

Bugfix:
 - Fix crash due to missing informationData (#535)
 - Progress in initial sync dialog is decreasing for a step and should not (#532)
 - Fix rendering issue of accepted third party invitation event
 - All current notifications were dismissed by mistake when the app is launched from the launcher

Build:
 - Fix issue with version name (#533)
 - Fix issue with bad versionCode generated by Buildkite (#553)

Changes in RiotX 0.4.0 (2019-08-30)
===================================================

Features:
 - Display read receipts in timeline (#81)

Improvements:
 - Reactions: Reinstate the ability to react with non-unicode keys (#307)

Bugfix:
 - Fix text diff linebreak display (#441)
 - Date change message repeats for each redaction until a normal message (#358)
 - Slide-in reply icon is distorted (#423)
 - Regression / e2e replies not encrypted
 - Some video won't play
 - Privacy: remove log of notifiable event (#519)
 - Fix crash with EmojiCompat (#530)

Changes in RiotX 0.3.0 (2019-08-08)
===================================================

Features:
 - Create Direct Room flow
 - Handle `/markdown` command

Improvements:
 - UI for pending edits (#193)
 - UX image preview screen transition (#393)
 - Basic support for resending failed messages (retry/remove)
 - Enable proper cancellation of suspending functions (including db transaction)
 - Enhances network connectivity checks in SDK
 - Add "View Edit History" item in the message bottom sheet (#401)
 - Cancel sync request on pause and timeout to 0 after pause (#404)

Other changes:
 - Show sync progress also in room detail screen (#403)

Bugfix:
 - Edited message: link confusion when (edited) appears in body (#398)
 - Close detail room screen when the room is left with another client (#256)
 - Clear notification for a room left on another client
 - Fix messages with empty `in_reply_to` not rendering (#447)
 - Fix clear cache (#408) and Logout (#205)
 - Fix `(edited)` link can be copied to clipboard (#402)
 - KeyBackup / SSSS | Should get the key from SSSS instead of asking recovery Key (#1163)

Build:
 - Split APK: generate one APK per arch, to reduce APK size of about 30%


Changes in RiotX 0.2.0 (2019-07-18)
===================================================

Features:
 - Message Editing: View edit history (#121)
 - Rooms filtering (#304)
 - Edit in encrypted room

Improvements:
 - Handle click on redacted events: view source and create permalink
 - Improve long tap menu: reply on top, more compact (#368)
 - Quick reply in timeline with swipe gesture (#167)
 - Improve edit of replies
 - Improve performance on Room Members and Users management (#381)

Other changes:
 - migrate from rxbinding 2 to rxbinding 3

Bugfix:
 - Fix regression on permalink click
 - Fix crash reported by the PlayStore (#341)
 - Fix Chat composer separator color in dark/black theme
 - Fix bad layout for room directory filter (#349)
 - Fix Copying link from a message shouldn't open context menu (#364)

Changes in RiotX 0.1.0 (2019-07-11)
===================================================

First release!

Mode details here: https://medium.com/@RiotChat/introducing-the-riotx-beta-for-android-b17952e8f771
