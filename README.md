# Anikura Android TV

Kotlin + WebView wrapper for https://anikura.club/ (Android TV, D-pad).

## Build

### Option 1: GitHub Actions (recommended)
1. Go to the **Actions** tab of this repository.
2. Select the **Build APK** workflow.
3. Click **Run workflow** (or push to `main`).
4. When finished, download the artifact **AnikuraTV-debug** — it contains the APK.

### Option 2: Local (Android Studio)
Open in Android Studio (JDK 17), let Gradle sync, then **Build > Build APK(s)**.

## Files
- `app/src/main/java/club/anikura/tv/MainActivity.kt` – Back/media key handling, fullscreen, logging bridge, native search overlay.
- `app/src/main/assets/tv-nav.js` – injected focus ring, D-pad movement, activation guard, Back logic.

## Debugging
`adb logcat -s AnikuraTV` shows navigation, history.pushState/popstate, every click (target + label), suppressed duplicate activations, and Back results.

## Known limits
- The /watch/ page blocks automated inspection, so the real DOM of the details modal's X and the player controls was not inspected. Back finds the modal via role=dialog/aria-modal and the close button via label or top-right icon button; if the site's markup differs, send the modal HTML.
- Controls inside a cross-origin iframe player cannot be reached by injected JS.

## Full-screen anime search overlay
- Dark full-screen overlay with white text and white focus outlines.
- Open using the on-screen Search shortcut or the remote's Search key (where available).
- D-pad navigation, OK/Select activation, Back/Escape closes the overlay.
- Search submits into Anikura's existing visible search field; no undocumented endpoint is assumed.
- Search results remain rendered by the website.
