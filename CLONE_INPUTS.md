# Clone inputs — edit the "New value" column and tell me to apply

Source: `../CallerIDLookupHome` @ `adadab0` (`com.callerid.numberlookup.home`, versionCode 1 / 1.0.0)
Clone:  this folder, branch **master**

Legend:
- **[SET]** — already applied.
- **[NEEDS YOU]** — still the source app's value, or a placeholder. It builds, but this must be real before release.
- **[KEPT]** — deliberately unchanged. Reason given.

---

## A. Identity — Stage 2 (`ab38e88`)

| # | Item | Source | Current value |
|---|---|---|---|
| A1 | applicationId / namespace **[SET]** | `com.callerid.numberlookup.home` | `com.callerid.phonelookup.home` |
| A2 | Ad-module package **[SET]** | `com.callerid.adbridge` | `com.callerid.adcast` |
| A3 | rootProject.name **[SET]** | `Caller ID Lookup Home` | `Caller ID Phone Home` |
| A4 | APK archive prefix **[SET]** | `CallerIdLookupHome` | `CallerIdPhoneHome` |
| A5 | Theme **[SET]** | `Theme.CallerIdLookupHome` | `Theme.CallerIdPhoneHome` |
| A6 | Play listing name | — | `Caller ID Phone Home` |
| A7 | versionCode / versionName **[KEPT]** | 1 / 1.0.0 | 1 / 1.0.0 |
| A8 | minSdk / targetSdk **[KEPT]** | 26 / 36 | 26 / 36 |

## B. Names shown to users — **[SET]**, all 12 locales

| # | Item | Value |
|---|---|---|
| B1 | `app_name` | `Caller ID` |
| B2 | `app_label` (launcher) | `␣␣Caller ID` — keeps the two non-breaking spaces that sort the app to the top of system lists |
| B3 | `app_name_overlay` | `Caller ID` |

## C. Brand visuals — **[NEEDS YOU]**

| # | Item | State |
|---|---|---|
| C1 | Launcher icon | inherited from the source: a single `mipmap-xhdpi/ic_launcher.png`, no adaptive icon (the source removed the other densities in `db479f6`) |
| C2 | Palette | `values/colors_cid.xml` + `values-night` are the source's blue ramp, byte-identical |
| C3 | Splash gradient | `#046DFF → #0A95FF → #0BD1FF`, byte-identical |
| C4 | Layouts | byte-identical apart from the package/theme rename |

## D. Keys and endpoints

| # | Item | Where | Status |
|---|---|---|---|
| D1 | Backend URL + API credentials | `services/RetrofitClient.kt`, `ServiceCredentials.kt` | **[KEPT]** your own backend |
| D2 | `google-services.json` | `app/` | **[NEEDS YOU]** package_name rewritten so the build resolves, but still project `caller-id-home` |
| D3 | LightHouse API key / base URL | `local.properties` | **[NEEDS YOU]** commented out — the SDK is visibly unconfigured rather than silently empty |
| D4 | Remote Config | `docs/remote-config.json` | **[NEEDS YOU]** not published for this app; screen names still match the source's class names |
| D5 | AdMob app id | `AndroidManifest.xml` | **[NEEDS YOU]** Google's test id |
| D6 | Ad unit ids | `docs/remote-config.json` | **[NEEDS YOU]** all test units |
| D7 | Signing keystore | — | **[NEEDS YOU]** deliberately not copied; generate one for this listing |
| D8 | Policy / terms URLs | `strings.xml`, `docs/remote-config.json` | **[NEEDS YOU]** point at `sites.google.com/view/calleridphonelookup`, the original app's site |

## E. Deliberately left alone — **[KEPT]**

| # | Item | Why |
|---|---|---|
| E1 | `conduit.user` / `conduit.password` | `gradle.properties` private-maven credentials; the build cannot resolve the LightHouse SDK without them |
| E2 | Ad-SDK native layouts (13) | AdMob/FAN bind those views by reference; renaming breaks ad rendering |
| E3 | Class and resource names | Identical to the source app — Stage 3 is what changes them |

---

## Still outstanding

1. **Stage 3 refactor** — class, layout, drawable and view-id renames. Everything currently matches the source app name-for-name.
2. **Brand visuals** (section C) — icon and palette.
3. **Firebase project + google-services.json** (D2), then publish Remote Config (D4).
4. **Real AdMob ids** (D5, D6) and **LightHouse key** (D3).
5. **Own signing keystore** (D7) and **policy URLs** (D8).
