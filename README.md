# PressIA — successeur moderne de Press (lecteur Feedly/offline)

UI et fonctions reprises de **Press 1.5.4** (APK d'origine, package `com.twentyfivesquares.press`, non distribué avec ce code) :
- Drawer catégories / abonnements, flux stream, lu/non-lu, favoris, thèmes clair/sombre (+AMOLED), partage système, hors-ligne complet, sync planifiée, Wi-Fi only, taille max du cache, images offline, taille police, vues liste/carte/magazine, article complet via double-tap (Readability maison).
- Non dépendant de Feedly Pro : fonctionne avec le niveau gratuit Feedly Cloud v3, **Inoreader** (`https://www.inoreader.com/`), ou **100 % local RSS/Atom** sans compte.

Ajouts demandés :
- **Recherche avec périmètre** : racine = tous les flux ; dossier/flux = périmètre courant (serveur si dispo, sinon LIKE offline).
- **Déduplication** : hash titre+lien (`dedupHash`), écran Doublons, marquage lu manuel / « tout sauf le + récent ».
- **Stats de lecture** : total 30 j, top flux, activité/jour (tables `reading_events` + `extract_events`).
- **Multi-comptes** : plusieurs comptes Feedly/Inoreader coexistent, chacun identifié par `service#empreinte-du-jeton` (`Accounts.kt`), sans jamais se confondre.
- **Sauvegarde/restauration** : export JSON sectionné — préférences, stats, comptes/flux, favoris ; les jetons ne sont jamais exportés par défaut (`Backup.kt`).

## Structure
- `data/local` : Room (`categories`, `subscriptions`, `articles`, `sync_queue`, `reading_events`, `extract_events`) + DataStore `Prefs`.
- `data/remote` : interface `FeedProvider` (`FeedProvider.kt`) et `Providers.kt` (`FeedlyProvider` OAuth2 + `cloud.feedly.com/v3`, `InoreaderProvider` (`inoreader.com/reader/api/0`), `LocalProvider`), `CloudApis.kt` (modèles + services Retrofit/kotlinx-serialization), `LocalRssFetcher.kt` (parsing RSS/Atom), `OAuth` (URLs d'autorisation, échange code→jeton, deep link `pressia://oauth`).
- `data` : `PressRepository` offline-first (lecture Room, `syncDown`, `pushQueue` bidirectionnelle, `ensureFull`, `syncReadLater`), `Accounts.kt` (registre de comptes), `Backup.kt` (export/import JSON).
- `data/sync` : `SyncWorker` (WorkManager périodique, contraintes Wi-Fi, synchro « Read later ») ; miniatures via `ThumbStore` (`SHA-1(url).jpg`, taille de la plus grande miniature affichée) et `syncThumbs()` (appelé en fin de `syncDown`, `syncReadLater` et `backfill`).
- `data/readability` : extraction article complet Jsoup (double-tap).
- `di` : `Locator` (instanciation manuelle du repository, partagée UI et WorkManager).
- `ui` : Compose Material3 façon Press (drawer, stream, abonnements, article, recherche, doublons, stats, réglages, comptes) autour du `PressVM` ; `Components.kt` et `ExtraScreens.kt` regroupent les écrans secondaires.
- Vues articles : liste / carte / magazine / **compacte** (favicon ou initiale, petits titres/dates).
- Accueil au choix : **Abonnements** (dossiers + compteurs non-lus, façon Press) ou **Tous les flux**.
- Bouton de raccourci par ligne au choix : favori / lu-non-lu / partage.
- Article : bouton marquer non-lu, site d'origine, drawer accessible, pas de titre redondant.
- Réglages : tailles de police titres/dates/textes, thème aussi dans le drawer, cache jusqu'à 10 000 articles/flux, fréquence de sync séparée.
- Article : titre redimensionnable et cliquable (navigateur), site + date sur une ligne, image principale + images inline, notice FR si extraction bloquée.
- Couleur dominante configurable (rouge/bleu/vert/violet/orange), pastilles lu/non-lu grises, favicon dans le drawer et le top-bar, toggle rapide lus/non-lus (œil), snackbar de résultat de sync, surbrillance drawer pilotée par la navigation (retour arrière correct).
- Favicons réels (service `s2/favicons` via host, cache Coil offline, initiale en repli), nom de site `prettyFeedTitle`, palettes couleur dominante + fond, titre d'article redimensionnable/cliquable avec images inline, pastilles grises réduites, thème dans l'en-tête du drawer.

## Comptes (100 % in-app, sans navigateur externe)
**Feedly : aucun identifiant à renseigner.** Écran **Comptes → « Se connecter avec Feedly »**
(`FeedlySignInScreen`) : la page de connexion officielle s'ouvre dans une WebView intégrée,
l'utilisateur y saisit ses identifiants habituels (e-mail/mot de passe, Google, Apple),
puis PressIA récupère automatiquement le jeton de session (stockage web, cookies en filet),
le vérifie, enregistre le compte et revient. Pas de `FEEDLY_CLIENT_ID` à créer : ce flux
utilise le `client_id=feedly` public (+ PKCE).
**Inoreader et variante OAuth générique** (`OAuthWebViewScreen`, comme le `FeedlyOAuthActivity`
du Press d'origine) : la WebView charge la page d'autorisation, intercepte le retour
`pressia://oauth/…`, échange le code contre un jeton (`OAuth.feedlyToken` / `inoreaderToken`)
et enregistre le compte. Ce flux-ci exige des identifiants d'application, renseignez :
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

Min SDK 26, target/compile 34, Kotlin 2.0, Compose BOM 2024.10, Material3 1.3, Room 2.6.1, Paging 3.3, DataStore 1.1, WorkManager 2.9, Coil 2.6, Retrofit 2.11 / OkHttp 4.12 / kotlinx-serialization 1.7, Jsoup 1.17.

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
