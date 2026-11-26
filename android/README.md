# みどりの記録 Android app

This Android Studio project packages the current web app for offline use and forwards watering reminders to Android notifications.

## Build

1. Open this `android` folder in Android Studio.
2. Install Android SDK Platform 35 when prompted.
3. Use JDK 17 for Gradle.
4. Build with **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Enable notifications from the app's reminder settings to grant Android notification permission. The app syncs the current watering plan to Android and schedules local reminders, so due reminders can appear while the app is closed. Android power management may delay the scheduled time. Weather data is refreshed only while the app is open.

## Optional AI plant assistant

The plant assistant works offline by default. Without AI, enable the search checkbox to show a DuckDuckGo link for broad web results. If a plant is selected, the app also queries Japanese Wikipedia by plant name and shows matching article excerpts and sources. This does not require an API key or incur AI search charges. The selected plant name is sent to Wikipedia; the question is included in the DuckDuckGo link and sent there only when opened. To enable Gemini or ChatGPT (OpenAI API), follow `../GEMINI-AI-SETUP.html` and deploy the Cloudflare Worker from `../cloudflare/worker.js`. Keep the selected provider's API key in the Worker secret (`GEMINI_API_KEY` or `OPENAI_API_KEY`); never add it to the app or APK. The provider and Worker URL are saved on the device and can be changed later. With a connection configured, the same checkbox uses the selected AI provider's web search and shows sources; this may incur charges.

For plants not in the built-in catalog, enter a name in the add-plant dialog and choose **Register by name only**. Without AI, **Search care online** queries Japanese Wikipedia for plant information, uses curated care intervals if the name matches the built-in catalog, and otherwise creates a clearly labeled generic starter plan. Review the source and assumptions before registering; Wikipedia may not contain care instructions, and generic intervals are only starting estimates. The plant name is sent to Wikipedia. If an AI Worker connection and consent are saved, the same button uses AI web search instead and may incur charges.

Watering plans start from each plant's seasonal care interval and adjust against recent and forecast evapotranspiration; for plants marked outdoors, the forecast rain can delay the check date. Every successful weather refresh recalculates all plant plans. Stale or unavailable weather falls back to seasonal intervals. In reminder settings, allow notifications and set weather location. Choose automatic reminder time to use the next three days' forecast highs: 07:00 at 30°C or higher, 08:00 at 25°C or higher, 10:00 during a cold winter forecast (below 10°C), and otherwise 09:00. Stale or unavailable weather falls back to seasonal guidance (08:00 in summer, 10:00 in winter, 09:00 otherwise), and the time is recalculated when weather is refreshed. Manual reminder time is also available. The app requests coarse location permission, rounds coordinates to about 1 km, then fetches weather forecasts and 30-day history from Open-Meteo. Use **Update weather now** to refresh, or **Refresh current location** after moving. Automatic updates on app open or resume can be enabled or disabled and are limited to once every 12 hours. Rounded coordinates are stored on-device and sent to Open-Meteo for weather requests; its free API is for non-commercial use. Android schedules local reminders while the app is closed, subject to Android power-management delays. A reminder is a prompt to check the soil, not an instruction to water automatically. Rebuild the APK after updating the Android assets.

## Plant care and records

The **Today's care** list shows only plants whose check date is due. Plant cards explain the watering plan and identify whether care guidance comes from the built-in catalog, a Wikipedia source, or a generic estimate. The weather model does not measure soil moisture.

Attach a photo to a growth note to keep a compressed copy in this browser's on-device IndexedDB; photos are not synced across devices. Plant-specific light and location notes are included as context for the assistant but do not change the watering interval. The symptom checklist suggests observations and next steps; it does not diagnose plant diseases. Reminder notifications can be grouped, with quiet hours from 21:00 to 07:00. Recent plant additions, edits, logs, and deletions can be undone for about 10 seconds.
