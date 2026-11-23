# WiGLE Offline Navigator

Offline-Positionsbestimmung (ohne GPS, ohne Internet) über eine lokale WiGLE-WLAN-Datenbank.
Die berechnete Position wird per Android-Mock-Location systemweit an Karten-Apps (OsmAnd, Organic Maps, …) weitergegeben.

**APK:** [`dist/WiGLE-Offline-Navigator-1.0.0.apk`](dist/WiGLE-Offline-Navigator-1.0.0.apk) (minSdk 26 / Android 8.0, targetSdk 35)

## Architektur

| Datei | Aufgabe |
|---|---|
| `Database.kt` | Room: `AccessPointEntity` (bssid lowercase, unique Index), DAO mit Count-Flow, Chunked-IN-Abfrage, Clear |
| `CsvImporter.kt` | SAF-Import von WiGLE-CSV (auch `.csv.gz`), Pre-Header-Erkennung, RFC-4180-Parser, Filter `Type != WIFI` / `(0,0)` / ungültige MAC, Batch-Insert à 5000 |
| `LocationCalculator.kt` | Weighted Centroid `w = 10^(rssi/10)`, Top-12 APs, Median-Ausreißerfilter (>500 m), Genauigkeit aus Log-Distance-Modell + Streuung |
| `WifiNavService.kt` | Foreground-Service (`location`), `startScan()` alle 4 s + `SCAN_RESULTS_AVAILABLE_ACTION`, Abgleich DB, Mock-Location für GPS+Network-Provider, 1-Hz-Republish, `MockLocationPublisher` |
| `MainActivity.kt` | Compose-UI: Status, Live-Position, AP-Liste (SSID/BSSID/RSSI/Gewicht), DB-Verwaltung, Warnungen, ADB-Hinweis |

## Einrichtung am Smartphone

1. APK installieren (Installation aus unbekannten Quellen erlauben).
2. **Entwickleroptionen aktivieren:** Einstellungen → Über das Telefon → 7× auf „Build-Nummer“ tippen.
3. **Mock-App wählen:** Entwickleroptionen → „App für simulierte Standorte auswählen“ → *WiGLE Offline Navigator*
   (der Button in der App öffnet die Entwickleroptionen direkt).
4. **Scan-Drosselung abschalten** (sonst max. 4 Scans / 2 min):
   - Entwickleroptionen → „Drosselung der WLAN-Suche“ → aus, **oder**
   - per PC: `adb shell settings put global wifi_scan_throttle_enabled 0`
5. **Standort (Location) aktiviert lassen** – Android liefert sonst keine WLAN-Scan-Ergebnisse. WLAN muss an sein (oder „WLAN-Suche“ aktiv), eine Verbindung ist nicht nötig.
6. Optional: Akku-Optimierung für die App deaktivieren.
7. WiGLE-Export (wigle.net → Uploads → CSV, oder WiGLE-App → Datenbank → CSV exportieren) über **CSV importieren** laden.
8. **Navigation starten**, Berechtigungen (Standort, Benachrichtigungen, WLAN-Geräte in der Nähe) erteilen, dann Karten-App öffnen.

## Build

```bash
export ANDROID_HOME=/pfad/zum/android-sdk
./gradlew testDebugUnitTest assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Release ist mit dem Debug-Key signiert (direkt installierbar). Für Verteilung eigenen `signingConfig` eintragen.
GitHub Actions (`.github/workflows/android.yml`) baut die APK bei jedem Push als Artifact.
