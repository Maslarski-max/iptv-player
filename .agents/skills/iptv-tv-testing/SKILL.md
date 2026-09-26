---
name: iptv-android-tv-testing
description: Boot an Android TV emulator and record an IPTV Compose app walkthrough using remote-control navigation.
---

# Android TV UI testing

## Environment
- Use the supplied APK where available. SDK used here: `/home/ubuntu/android-sdk`; Java 17.
- Install emulator and an available TV image with `sdkmanager "emulator" "system-images;android-34;android-tv;x86"`; API 34 TV may offer x86 rather than x86_64.
- Create the AVD: `echo no | avdmanager create avd -n iptv_tv -k "system-images;android-34;android-tv;x86" -d tv_1080p`.
- Run `emulator -accel-check`. `/dev/kvm` can exist without user access; arrange group/ACL access. In the disposable environment, passwordless `sudo chgrp ubuntu /dev/kvm` made KVM usable; prefer normal kvm group membership for reusable environments.
- Launch a visible emulator: `ANDROID_HOME=/home/ubuntu/android-sdk emulator -avd iptv_tv -gpu swiftshader -no-snapshot -no-boot-anim`. `-no-audio` is useful for silent UI recordings but explicitly excludes audible playback verification.
- Wait for `adb shell getprop sys.boot_completed` to return `1`, then `adb install /home/ubuntu/iptv-player-debug.apk` and `adb shell am start -n com.maslarski.iptv/.MainActivity`.
- Default tv_1080p device is 1920×1080, density 320. Preserve this when testing actual TV layout.

## Recording and navigation
- Maximize the emulator with wmctrl. Some emulator Qt versions flag maximized without scaling their guest display properly; if needed fit desktop resolution to the native window instead (verified: `xrandr --output VNC-0 --mode 1024x768`, window moved to x=0,y=40). Do not change guest density to hide layout bugs.
- Use `adb shell input keyevent DPAD_UP/DPAD_DOWN/DPAD_LEFT/DPAD_RIGHT/DPAD_CENTER/BACK`.
- Send selection (`DPAD_CENTER`) separately after navigation so Compose focus can settle; batches of key events can act on stale focus.
- In text fields, use `adb shell input text 'Public%sTV'` (`%s` means space). BACK hides TV IME, then TAB moves out of text fields. TAB can be a diagnostic workaround for D-pad-inaccessible controls; report the workaround, do not pass D-pad accessibility.
- Settings → Manage playlists → Add playlist offers URL M3U and a local document picker. Required name/URL enable Save. Use `https://iptv-org.github.io/iptv/index.m3u`; source channel counts change over time.
- Public M3U groups may be classified into Movies; expect empty Series if no episode entries. Without EPG input, Guide can show channels/timeline but "No program information."
- Public stream availability varies. Verified one channel on this run; do not infer all imported channels work. A small local M3U containing public HLS test streams is an appropriate fallback if URL import or streams fail.
- Search requires at least two characters. Use a known channel name and a no-match query.
- Capture `adb logcat -d --pid=$(adb shell pidof com.maslarski.iptv)` and `adb logcat -b crash -d` after playback; separate emulator EGL/codec warnings from fatal application crashes.

## Locale-switching checks
- Language choices live in a horizontally scrolling Settings row; move up to it after entering from the side rail because spatial D-pad navigation may initially focus a playback switch.
- The last two choices are Македонски and Srpski / Hrvatski (`sr-Latn`). Capture the focused pill before pressing Center; translated text widths can change which pill spatial navigation reaches.
- Check immediate updates without restarting, then explicitly `adb shell am force-stop com.maslarski.iptv` and relaunch to verify persistence. Restore English through the UI afterward.
- Scrolling past the TMDB text field may open the TV IME; use BACK to dismiss it before continuing to the parental heading. Do not enter or save a key during localization-only testing.

## Devin Secrets Needed
- None for public M3U import and playback.
- Optional `TMDB_API_KEY` for independent metadata enrichment verification; a supplied APK may already contain a built-in key. Do not expose key values in screenshots or logs.

## Guide, favorites, and player coverage
- When public playlists lack EPG, add a clearly labeled supplemental M3U/XMLTV fixture through the playlist UI. Serve only its directory with `python -m http.server 8765 --directory /path/to/fixture`; the emulator reaches it at `http://10.0.2.2:8765/`. Match `tvg-id` values to XMLTV channel IDs, and generate future UTC programme times using the emulator's date. Explicitly distinguish synthetic programme schedules from actual public-stream video in the report.
- Favorites queue tests need at least two starred channels and an unstarred channel between them. Use `adb shell input keyevent --longpress DPAD_CENTER`; verify both the current route and focused card afterward. Check channel names while wrapping, not just identical test-stream pictures.
- Hidden live-player OSD: Left opens channels, Right opens settings; Up/Down now reveal controls without zapping. Use CHANNEL_UP/CHANNEL_DOWN for channel changes. Panels hold the OSD visible until closed.
- For VOD timeline tests, pause before comparing exact -10/+30-second changes. Up from Play/Pause focuses the timeline; Down returns. Automatic OSD hide requires playing; Back while paused can exit playback rather than hide controls.
- After long-OK toggling, test the next short OK separately: a toggle can work while leaving a stale key state that consumes the following activation. Verify visible heart updates independently of Favorites membership, and verify membership again after leaving/reopening Favorites (unfavorited placeholders may remain to preserve focus).
- Include a deterministic series fixture title such as `Test Series S01 E01` in an M3U Series group when testing series-specific UI. Metadata enrichment may find unrelated artwork; synthetic title/stream fixtures do not validate metadata matching.
- Short-lived player controls may disappear before a screenshot tool call completes. Send a key and immediately run `adb exec-out screencap -p > screenshot.png` in the same shell invocation. Timestamp both capture start and completion; PNG compression/transfer can take several seconds, so do not equate command completion with pixel-capture time.
- To exercise buffering without modifying streams, use `adb emu network speed gsm` during playback and wait for a visible centered spinner (a frozen frame alone is insufficient). Verify hidden controls stay hidden, then reveal with Up and observe auto-hide while the spinner persists. Restore `adb emu network speed full`, confirm `adb emu network status` reports zero limits, and verify automatic playback recovery. Do this for both live and VOD modes; do not press Play merely because the icon becomes a triangle during buffering.
- Avoid rapid TAB batches through input fields: the TV IME may appear after focus already moves. Let each field and keyboard settle before Back/Tab. If input appears stuck, save screenshots and logs, try Home/relaunch, and distinguish an unresponsive UI from a logged ANR. Do not count Tab/touch workarounds as a D-pad pass.
- For D-pad-only form tests, dismiss the IME with Back at each focused field before pressing Down. Spatial focus can select the rightmost footer action (Done/Cancel) rather than Activate/Save; capture the actual focused button and distinguish direct Down behavior from a successful Down-then-Left path. If cold-start rendering looks stale, verify a first directional event and fresh screenshot before assuming an app hang.

## Category management
- Use a supplemental playlist with at least three live categories, three distinct channel names per category, two movie categories, and a series category. Give stream URLs distinct query parameters so importer IDs do not collide. `group-title` containing Movies or Series controls the M3U content classification.
- Settings → Manage Categories & Channels appears under the playlist controls once a playlist exists. On returning to Settings, scroll and focus may be retained; inspect the current row rather than blindly replaying a fixed number of Down events. Right from the rail can land on a visible lower settings control instead of the first account action.
- OK enters purple Move Mode; verify the same item remains focused through each Up/Down swap. Independently test Back in category and channel Move Mode, Back outside Move Mode, Right expansion, and Left collapse from a child.
- Verify actual destination lists after every visibility change. Hiding a category should mark all expanded children hidden and remove them from All, not only remove the category label. Unhiding must restore saved channel order. Counts may represent imported items rather than currently visible rows.
- Force-stop/relaunch and inspect both category order and channel order for persistence. A fresh emulator data directory does not prove migration retention even when `adb install -r` succeeds.
