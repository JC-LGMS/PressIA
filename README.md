# PressIA — successeur moderne de Press (lecteur Feedly/offline)

UI et fonctions reprises de **Press 1.5.4** (`Press 1.5.4 du 20250808.apk`, package `com.twentyfivesquares.press`) :
- Drawer catégories / abonnements, flux stream, lu/non-lu, favoris, thèmes clair/sombre (+AMOLED), partage système, hors-ligne complet, sync planifiée, Wi-Fi only, taille max du cache, images offline, taille police, vues liste/carte/magazine, article complet via double-tap (Readability maison).
- Non dépendant de Feedly Pro : fonctionne avec le niveau gratuit Feedly Cloud v3, **Inoreader** (`https://www.inoreader.com/`), ou **100 % local RSS/Atom** sans compte.

Ajouts demandés :
- **Recherche avec périmètre** : racine = tous les flux ; dossier/flux = périmètre courant (serveur si dispo, sinon LIKE offline).
- **Déduplication** : hash titre+lien, écran Doublons, marquage lu manuel / « tout sauf le + récent ».
- **Stats de lecture** : total 30 j, top flux, activité/jour (table `reading_events`).

## Structure
- `data/local` : Room (`categories`, `subscriptions`, `articles`, `sync_queue`, `reading_events`) + DataStore `Prefs`.
- `data/remote` : `FeedProvider` + `FeedlyProvider` (OAuth2 + `cloud.feedly.com/v3`), `InoreaderProvider` (`inoreader.com/reader/api/0`), `LocalProvider` + `LocalRssFetcher`, `OAuth` (Custom Tabs + deep link `pressia://oauth`).
- `data` : `PressRepository` offline-first (lecture Room, `syncDown`, `pushQueue` bidirectionnelle, `ensureFull`).
- `data/sync` : `SyncWorker` (WorkManager périodique, contraintes Wi-Fi, préfetch images Coil).
- `data/readability` : extraction article complet Jsoup (double-tap).
- `ui` : Compose Material3 façon Press (drawer, stream, article, recherche, doublons, stats, réglages, comptes).
- Vues articles : liste / carte / magazine / **compacte** (favicon ou initiale, petits titres/dates).
- Accueil au choix : **Abonnements** (dossiers + compteurs non-lus, façon Press) ou **Tous les flux**.
- Bouton de raccourci par ligne au choix : favori / lu-non-lu / partage.
- Article : bouton marquer non-lu, site d'origine, drawer accessible, pas de titre redondant.
- Réglages : tailles de police titres/dates/textes, thème aussi dans le drawer, cache jusqu'à 10 000 articles/flux, fréquence de sync séparée.
- Article : titre redimensionnable et cliquable (navigateur), site + date sur une ligne, image principale + images inline, notice FR si extraction bloquée.
- Couleur dominante configurable (rouge/bleu/vert/violet/orange), pastilles lu/non-lu grises, favicon dans le drawer et le top-bar, toggle rapide lus/non-lus (œil), snackbar de résultat de sync, surbrillance drawer pilotée par la navigation (retour arrière correct).
- Favicons réels (service `s2/favicons` via host, cache Coil offline, initiale en repli), nom de site `prettyFeedTitle`, palettes couleur dominante + fond, titre d'article redimensionnable/cliquable avec images inline, pastilles grises réduites, thème dans l'en-tête du drawer.

## Config OAuth (100 % in-app, sans navigateur externe)
Écran **Comptes → Feedly / Inoreader → « Se connecter »** : une WebView intégrée
(`OAuthWebViewScreen`, comme le `FeedlyOAuthActivity` du Press d'origine) charge la page
d'autorisation, intercepte le retour `pressia://oauth/…`, échange le code contre un jeton
(`OAuth.feedlyToken` / `inoreaderToken`) et enregistre le compte. Renseignez :
```
FEEDLY_CLIENT_ID=...
FEEDLY_CLIENT_SECRET=...
INOREADER_APP_ID=...
INOREADER_APP_KEY=...
```
Redirect URIs (à déclarer telles quelles dans votre app Feedly/Inoreader) :
`pressia://oauth/feedly`, `pressia://oauth/inoreader` (aussi déclarées en deep link manifest en secours).

## Build
```powershell
$env:FEEDLY_CLIENT_ID="..."
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Min SDK 26, target/compile 34, Kotlin 2.0, Compose BOM 2024.10, Room 2.6.1, WorkManager 2.9, Coil 2.6, Jsoup 1.17.

## Correspondance Press → PressIA
| Press (APK) | PressIA |
|---|---|
| `HomeActivity` + drawer labels | `PressDrawer` + `StreamScaffold` |
| `FeedActivity` / WebView article | `ArticleScreen` (double-tap Readability, partage) |
| `SearchActivity` | `SearchScreen` (périmètre) |
| Readability toggle | `Readability.fetchFullResult` : `Ok` / `Blocked` (anti-robot, paywall, page JS — notice en français, extrait RSS conservé) / `Failed` |
| `SyncService` + `SyncBroadcastReceiver` | `SyncWorker` + `pushQueue`/`syncDown` |
| Provider SQLite `PressProvider` | Room `PressDatabase` |
| Prefs (font, theme, wifi, cache) | DataStore `Prefs` + `SettingsScreen` |
| Readability toggle | `Readability.fetchFull` |
