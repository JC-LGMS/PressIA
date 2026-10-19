# PressIA — notes pour les agents

## Projet

Lecteur RSS Android (Feedly / Inoreader / flux locaux). **100 % Jetpack Compose** : il n'existe
aucun `res/layout`, aucun ViewHolder/Adapter, aucun `viewBinding`. Les 4 vues articles
(`list` / `card` / `magazine` / `compact`) sont des branches d'un `when (mode)` dans
`ArticleRow` (`app/src/main/java/com/pressia/ui/Components.kt`).

- package / applicationId : `com.pressia`
- activité launcher : `com.pressia.MainActivity`
- compileSdk / targetSdk 34, minSdk 26
- AGP 8.5.2, Kotlin 2.0.20, KSP 2.0.20-1.0.25, Gradle 8.7
- unique test unitaire : `app/src/test/java/com/pressia/PressLogicTest.kt` (pas de androidTest)

## Chaîne d'outils (machine-specific)

`gradle` **n'est pas dans le PATH** et `gradlew.bat` n'est qu'un stub (`gradle %*`) : il n'y a ni
`gradle-wrapper.jar` ni distribution dans `~/.gradle/wrapper/dists`. Le `README.md` dit
`./gradlew`, **ça ne marche pas sur cette machine**. Utiliser les copies déjà présentes dans
le dossier tools temporaire :

| Outil | Chemin |
|---|---|
| JDK 17 | `%LOCALAPPDATA%\Temp\opencode\tools\jdk-17.0.20.1+1` |
| Gradle 8.7 | `%LOCALAPPDATA%\Temp\opencode\tools\gradle-8.7\bin\gradle.bat` |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` (cf. `local.properties`) |
| adb | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` |
| emulator | `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe` (AVD **Pixel_10**) |

Ces outils sont hors du dépôt : s'ils disparaissent, les retélécharger (le ZIP de Gradle 8.7 est
normalement déjà dans le même dossier).

Préambule PowerShell à poser avant tout appel Gradle :

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Temp\opencode\tools\jdk-17.0.20.1+1"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$g = "$env:LOCALAPPDATA\Temp\opencode\tools\gradle-8.7\bin\gradle.bat"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
```

`--offline` fonctionne : les dépendances sont en cache dans `~/.gradle`. Ne le passer que si
rien n'a changé côté dépendances ; sinon le build échoue sur une résolution réseau.

## Compilation / tests (vérifiés)

```powershell
& $g :app:compileDebugKotlin --console=plain --offline    # vérif rapide après une modif
& $g :app:assembleDebug     --console=plain --offline    # APK debug
& $g :app:testDebugUnitTest --console=plain --offline    # tests unitaires
```

APK produit : `app/build/outputs/apk/debug/app-debug.apk`.

## Émulateur et pilotage de l'app (adb + UIAutomator)

Un émulateur est souvent déjà démarré sur `emulator-5554`. Sinon :

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Pixel_10
& $adb devices                                          # état des appareils
```

Installer / (re)lancer / logs :

```powershell
& $g :app:installDebug --console=plain --offline        # échoue si aucun émulateur lancé
& $adb install -r app/build/outputs/apk/debug/app-debug.apk              # variante manuelle
& $adb shell am start -n com.pressia/.MainActivity      # (re)lance l'app
& $adb logcat --pid=(& $adb shell pidof com.pressia)    # logs de l'app
```

Dumper l'interface (script utilitaire, équivalent aux deux commandes suivantes) :

```powershell
pwsh -NoLogo -NoProfile -File "$env:LOCALAPPDATA\Temp\opencode\dump.ps1"
& $adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml
& $adb -s emulator-5554 pull /sdcard/ui.xml "$env:LOCALAPPDATA\Temp\ui.xml"
```

`$env:LOCALAPPDATA\Temp\ui.xml` contient l'arborescence de l'écran courant. Repérer un node
par son text / content-desc / resource-id, calculer le centre de son bounds, puis :

```powershell
& $adb -s emulator-5554 shell input tap <X> <Y>
& $adb -s emulator-5554 shell input keyevent KEYCODE_BACK
& $adb -s emulator-5554 shell input swipe 540 1400 540 400 300
& $adb -s emulator-5554 shell "am force-stop com.pressia; monkey -p com.pressia -c android.intent.category.LAUNCHER 1"
```

Méthodologie : dump après chaque action, attendre 1–3 s selon la transition, re-dumper.

Tailles de dump typiques pour identifier l'écran :
- Menu contextuel (DropdownMenu) : ~4 KB (`ui.xml` ≈ 4061 octets)
- Liste d'articles : ~35–45 KB
- Détail d'article (WebView) : ~45–50 KB

### Simuler un appui long (long-press)

Sur l'émulateur, `input swipe X Y X Y DURATION` (même point de départ et d'arrivée) est
parfois interprété comme un simple tap, pas un vrai long-press. Deux astuces :

1. **`input motionevent DOWN` / `input motionevent UP`** — plus fiable :

   ```powershell
   & $adb -s emulator-5554 shell "input motionevent DOWN 540 473"
   Start-Sleep -Seconds 2
   & $adb -s emulator-5554 shell "input motionevent UP 540 473"
   Start-Sleep -Seconds 1
   ```

2. **Warm-up mandatory** — après `am force-stop` + redémarrage, le ComposeView
   n'enregistre pas immédiatement les gestes longs. Toujours faire un `input tap`
   (et `KEYCODE_BACK` pour revenir) **avant** de tenter le long-press :

   ```powershell
   & $adb -s emulator-5554 shell "input tap 540 473"     # warm-up
   Start-Sleep -Seconds 2
   & $adb -s emulator-5554 shell input keyevent KEYCODE_BACK  # retour liste
   Start-Sleep -Seconds 2
   & $adb -s emulator-5554 shell "input swipe 540 473 540 473 3000"  # long-press
   ```

3. Utiliser `monkey -p com.pressia -c android.intent.category.LAUNCHER 1` pour
   relancer l'app sans `force-stop` (plus rapide, le warm-up est déjà fait
   si l'app était au premier plan).

**Touches dans une WebView : viser ~90 px plus bas.** `input tap` atterrit environ 90 px
trop haut dans le contenu web (constaté : le bouton gris « Sign in with Google » se prenait
pour le vert « Sign in with Email » juste en dessous, les touches « Suivant » ouvraient les
CGU voisines). La WebView n'expose aucun node (opaque pour uiautomator) : passer par
capture d'écran (`screencap` + `read` du PNG, 1:1 avec l'écran 1080×2424) et compenser
`tap(X, Y+90)`. Champs hauts tolérants ; boutons ~80 px : rater = page voisine, revenir
en arrière via la flèche retour de l'app (jamais `KEYCODE_BACK` dans une page web : il
risque de quitter l'écran au lieu de fermer le clavier — préférer toucher une zone neutre
pour le replier).

**Flux RSS locaux de test : pas de HTTP en clair.** `LocalRssFetcher` passe par OkHttp, qui
refuse le HTTP en clair (OkHttp 4.12, `RealConnection` → `CLEARTEXT … not permitted by network
security policy`) dès lors que le manifeste ne déclare pas `usesCleartextTraffic` — cible
targetSdk 34, aucun `networkSecurityConfig` : l'échec est **silencieux** (l'app affiche « Flux
ajouté et synchronisé. » mais zéro article, et le serveur de test ne voit aucune requête).
Pour tester contre un serveur sur la machine hôte (`10.0.2.2`), poser un manifeste **de debug**
temporaire (`app/src/debug/AndroidManifest.xml`, `android:usesCleartextTraffic="true"`), le
retirer avant de committer. Un petit serveur Python maison (`ThreadingHTTPServer` sur
`0.0.0.0:8899`, flux `/fN.xml` + images `/img/N.jpg`) suffit : il compte les requêtes par URL,
ce qui vérifie en direct « une seule requête par image » et « tous les flux interrogés ».

## Images / miniatures (non intuitif, tout est là)

- Miniature **déclarée** par le flux (`imageUrl`, bannière de liste et image de tête) :
  `ThumbStore` (`data/local/ThumbStore.kt`), un fichier `SHA-1(url).jpg` dans `filesDir/thumbs`.
  Gabarit = **carré de la largeur d'écran** : c'est la plus grande taille à laquelle ce fichier est
  affiché (image de tête d'article, pleine largeur ; la bannière magazine de 180 dp en est un
  recadrage), donc toute image plus large que haute est enregistrée à la largeur exacte de l'écran
  (vérifié : 2400×1600 → 1080×720, contre 742×495 avec l'ancien gabarit « largeur × 180 dp »),
  jamais l'original full-size et jamais d'agrandissement à l'affichage. Une URL partagée ne se
  télécharge qu'une fois.
- Images **du corps** : le cache disque de Coil, par préchargement — même mécanisme que les
  favicons (`ImageRequest` + `execute`, `memoryCachePolicy(DISABLED)` et `size(1,1)` : c'est le
  cache disque qui compte, pas le décodage). Trop nombreuses pour un fichier dédié au gabarit.
  `Coil.setImageLoader` pose `respectCacheHeaders(false)`, donc elles sont rangées sous leur URL et
  l'affichage les sert sans réseau (`thumbModel()`, URL → cache Coil). Corollaire : `refreshFavicons()`
  ne vide **plus** tout le cache disque (il retire les seules entrées de favicons, `purgeImages`) —
  « Actualiser favicons » jetait sinon les images préchargées, définitivement (`imageCached` reste à 1).
- Suivi : `ArticleEntity.imageCached` = « toutes les images de cet article sont là ». `syncImages()`
  (`PressRepository`, sous `imagesMutex`) prend les articles à `imageCached = 0` (lus + non lus, 6 en
  parallèle, inactif si « Images hors-ligne » coupé ou « Wi-Fi uniquement » hors Wi-Fi), liste leurs
  images (`articleImageUrls` : miniature déclarée puis `<img>` de `content`/`summary`, dédoublonnées),
  met la miniature dans `ThumbStore` et les autres via Coil, puis marque l'article — une image
  manquante le laisse en attente (elle sera reprise ; marquage par article, pas par URL). Appelé en
  fin de `syncDown` (`withThumbs = true`) et de `backfill` : **toute** sync (bouton, lancement,
  worker, connexion de compte) récupère les images.
- **Ne jamais** relancer une passe depuis `syncReadLater` : le pipeline en lance déjà une en
  parallèle (`supervisorScope`), et deux passes simultanées lisaient la même liste d'articles et
  téléchargeaient deux fois les mêmes images (constaté : 26 images sur 32 demandées deux fois).
- `clearCache()` / `clearImages()` effacent les fichiers **et** remettent `imageCached` à 0 :
  la prochaine sync, quelle qu'elle soit, récupère tout. Ils purgent aussi les HTML extraits
  via Readability (`content = summary`, `fullFetched = 0`) : les articles sont conservés avec
  leur extrait RSS, la prochaine ouverture complète ré-extrait.
- Listes : les 3 `AsyncImage` d'`ArticleRow` passent par `thumbModel()` (fichier local si présent,
  sinon URL paresseuse au scroll — aucun préchargement de liste). **L'écran article aussi**
  (bannière et images du corps), par le même `thumbModel()`. Une URL déjà téléchargée à la synchro
  ne repart donc pas sur le réseau : fichier `ThumbStore` pour `imageUrl` — et pour la première
  image du corps de tout flux sans `enclosure`/`media:*`, puisque `LocalRssFetcher` prend alors la
  première `<img>` de la description (la miniature **est** la première image de l'article) —, cache
  disque Coil pour les autres. Vérifié en direct : une requête par image à la synchro, **zéro** à
  l'ouverture (contre une pour la 2e image du corps avant). Reste : la visionneuse (appui) garde
  l'URL d'origine, c'est là qu'on récupère la pleine qualité.

## Pièges connus

- Modifier une vue article = toucher `ArticleRow` dans `Components.kt`. Le `MetaLine` (source +
  étoile + heure) est partagé par les 4 vues ; lui donner un paramètre `modifier` plutôt que
  d'en dupliquer une variante.
- La position du rond lu/non-lu (`dotAtStart`) pilote aussi celle de l'étoile de swipe : les deux
  doivent rester du même côté, sinon l'information saute d'un bout à l'autre de la ligne au geste.
- Les champs de config OAuth (`FEEDLY_CLIENT_ID`, `INOREADER_APP_ID`, …) sont lus dans
  `local.properties` ou l'environnement ; ils sont vides par défaut et ne servent qu'au flux
  OAuth générique (`OAuthWebViewScreen`, utilisé par Inoreader). **Feedly n'en a pas besoin** :
  sa connexion passe par la page officielle intégrée (`FeedlySignInScreen`, identifiants
  saisis sur feedly.com, jeton de session récupéré automatiquement) — ne pas conclure
  qu'un compte distant ne peut pas s'authentifier sous prétexte que ces champs sont vides.
- Pas de migration Room à écrire pour une simple requête DAO ou colonne déjà existante ;
  `exportSchema = false`, version DB actuelle : 5.
- Réglage ajouté, modifié ou supprimé = cycle complet obligatoire : clé dans `Prefs`,
  état + `reloadFromPrefs`/`save()` dans l'écran Réglages, entrée dans `Prefs.ENTRIES`
  (sinon absent de l'export/import — test `entrees de preferences` à étendre), valeur
  par défaut cohérente partout où elle est lue.
- File montante : `pendingSync()` est une **page** (`LIMIT 100`), pas la file. Tout code qui
  raisonne sur « les actions en attente » doit passer par une requête dédiée
  (`pendingReadIds`, `pendingMarkAllCount`, `pendingSavedIds`, `pendingUnsavedIds`) ; `pushQueue`
  enchaîne les pages (plafond `PUSH_MAX_PAGES`). Ne jamais vider la file sur un état initial
  de `provider` (`drop(1)`), et une ligne sans flux vaut « tout le compte », pas « à jeter » —
  `pushPage` la pousse à **tous** les comptes cloud (branche « markAllRead »), le premier ne
  servant qu'à porter le groupe (`accountFor`) : la rattacher au seul premier laissait les
  articles des autres non lus côté serveur, rouverts par la réconciliation suivante.
- `pushQueue` passe par `pushMutex` — pas `syncMutex`, non réentrant, que `syncPipeline` détient
  déjà quand il appelle la file. Sans ce verrou, deux appels qui se chevauchent (lecture d'un
  article et pipeline, par exemple) relisaient la même page avant qu'elle ne se vide et
  poussaient deux fois les mêmes lignes : deux `file: N lignes poussées` identiques dans le log
  pour un seul marquage.
- Marquage de masse Feedly : `POST /v3/markers` accepte `type":"feeds"` (ou `"categories"`,
  avec ou sans `asOf`) puis les **ignore** — HTTP 200, corps vide, `markers/counts` inchangé
  (vérifié en direct). Seul `type":"entries"` marque vraiment : `FeedlyProvider.markAllRead` /
  `markAllReadGlobal` énumèrent donc les non-lus du flux (`streams/ids`, paquets de 100
  identifiants). Bonus : ça rattrape les articles que le serveur croit encore non lus alors que
  l'app les a marqués lus (marquage de masse d'une version précédente), que la réconciliation
  suivante rouvrirait sinon. Ne pas « optimiser » ce chemin en un appel de masse.
- Readability : `content` porte **soit** la version du flux, **soit** l'extraction — `fullFetched`
  dit laquelle. Couper le mode (icône, pastille, double-tap) doit **réécrire** `content` avec la
  version du flux et remettre `fullFetched` à 0 : sans cette écriture la copie Readability restait
  en base et l'article rouvert reprenait la version extraite. Avant d'écraser `content` par une
  extraction, `saveExtractedContent` recopie le HTML du flux dans `summary` quand il est plus riche
  que l'extrait RSS (flux « complets » : Next) — c'est ce que « Revenir à l'extrait RSS » retrouve,
  sinon l'article revenait tronqué au petit extrait. La synchro, elle, réécrit `content`/`summary`
  avec les valeurs du serveur (`upsertArticles` remplace la ligne) : l'extraction ne survit pas à
  une synchro, et c'est la version du flux qui revient — comportement voulu.
- `isSameImage` compare les noms de fichier **après nettoyage des variantes CDN**
  (`…-1024x495.webp`, `…__800_800__overflow.jpg`) : sans ça la vignette du flux (`imageUrl`)
  s'affichait en plus de la même image déjà présente dans le contenu (Les Numériques, Next).
- `articleBlocks` ne retient que `p, li, h1..h3, pre, blockquote, img` : certains flux livrent le
  corps en texte nu (`…texte…<br><img …>`, cas des Numériques). Les nœuds texte de premier niveau
  sont enveloppés dans un `<p>` avant sélection, sinon l'article s'affichait sans son texte.
- Tiroir (`MainActivity`) : `gesturesEnabled = drawer.isOpen || (current != "article" && !(current == "stream" && !atHome))`.
  Le balayage de bord est coupé là où le geste vers la droite a déjà un sens — article précédent sur
  l'écran article, retour à l'accueil sur une liste de dossier ou de flux (`StreamScaffold.onSwipeBack`)
  — mais **rétabli tiroir ouvert** : c'est ce qui permet de le refermer, le `onClose` du scrim
  Material étant conditionné par `gesturesEnabled` (sinon le clic hors du panneau ne fait rien).
- Readability « persistant » coupé : le mode complet épinglé retombe dès qu'on **quitte la vue
  article** (`LaunchedEffect(current)` dans `MainActivity` → `vm.onLeftArticle()`), pas seulement au
  retour à l'accueil ; le balayage d'un article à l'autre ne change pas de destination et le
  conserve. Un article déjà extrait, lui, reste en complet à la réouverture : c'est `fullFetched` en
  base, pas l'épingle de session.
- Réglages : les sections sont posées par `SettingsSection(titre)` (`ExtraScreens.kt`, filet + titre
  coloré) — un nouveau réglage rejoint la section de son usage.
- Flux RSS locaux (`provider = "local"`, identifiant = l'URL) : leur état lu est **purement local**
  (aucun serveur ne le renvoie). `syncDown` appelle donc `storeArticles(…, keepRead = true)` pour
  eux : `upsertArticles` remplace la ligne entière, et sans ce drapeau chaque synchronisation
  repassait tous les articles du flux en non lus. Les comptes cloud gardent l'inverse (l'état
  distant fait foi) — voir `mergeIncomingArticle`, où `unread`/`readAt` ne sont repris que si
  `keepRead`. Toute nouvelle écriture d'articles locaux doit passer par ce chemin.
- Pas de plafond sur le nombre de flux RSS locaux : `LocalProvider.stream` les interroge tous, en
  parallèle bornée (8 à la fois). Un `take(n)` en laissait silencieusement une partie sans articles
  (constaté avec 32 flux : 30 servis seulement).
- `deleteLocalFeed` = articles + miniatures orphelines (`gcThumbs`) + images pleine taille du cache
  Coil (`articleMediaOfFeed` → `htmlImageUrls` sur `content`/`summary` → `purgeImages`) + `vacuum()`.
  Les **traces de lecture restent** (voir la note « statistiques » plus bas). `gcThumbs` ne couvre
  que `filesDir/thumbs` ; une image encore réclamée par un autre flux reste en cache, et le
  nettoyage du cache Coil se voit dans le log (`images purgées : n/m`).
- Statistiques = registre conservé : les traces `reading_events`/`extract_events` **ne partent plus**
  avec un flux ni avec un compte (`deleteAccount`, désabonnement en synchro, `deleteLocalFeed`) —
  les effacer remettait les stats à zéro dès qu'on supprimait un compte. Leurs lectures s'affichent
  alors sous le libellé `DELETED_FEED_LABEL` (« Flux supprimé »), faute de titre en base. La remise à
  zéro est **manuelle** (bouton + confirmation sur l'écran Stats → `resetStats`) et pose
  `Prefs.STATS_SINCE`, borne basse appliquée par `statsLowerBound` : elle est indispensable au
  compteur « passés en lu sans ouvrir », déduit de `articles.readAt` (un état d'article que la remise
  à zéro ne touche pas). Nouvelle clé ⇒ entrée dans `Prefs.ENTRIES` + test des préférences.
- Suppression de masse (compte, flux, remise à zéro des stats) ⇒ `vacuum()` : `VACUUM` puis
  `PRAGMA wal_checkpoint(TRUNCATE)`. Sans le second, le `VACUUM` réécrit la base dans le WAL, le
  fichier principal garde sa taille, le WAL gonfle et « Cache utilisé » **monte** après une
  suppression (mesuré : 1,8 Mo → 120 Ko avec le point de contrôle, contre 1,8 Mo → 1,9 Mo sans).
  « articles » dans le détail du cache = les fichiers `pressia.db*` (db + WAL + shm), c'est pourquoi
  la taille ne redescend pas toute seule quand les articles partent.
- Toute suppression est **confirmée** : corbeille d'un flux local (`LoginScreen`, dialogue « Désabonner
  ce flux ? ») comme « Déconnecter » un compte. Les menus de flux des abonnements ne suppriment pas
  (déplacer / monter / descendre) — une nouvelle action destructrice doit passer par un dialogue.
- Ajout d'un flux local : sa **première synchronisation est annoncée** comme les autres
  (`lockSync()` + `syncMsg` dans `PressVM.addLocalFeed`, appels appariés en `finally`) — sans quoi
  l'ajout restait muet, sans bandeau ni snackbar, pendant tout le premier téléchargement.
- Périmètre orphelin (`MainActivity`) : quand le flux ou le dossier courant vient d'être supprimé
  depuis un autre écran (compte déconnecté, flux désabonné), un `LaunchedEffect` remet le périmètre
  sur « Tous les articles » et `lastContent` sur l'accueil — sinon le retour arrière remontait sur
  une liste vide, y compris après un détour par une autre page secondaire. La validation d'un
  dossier doit interroger `vm.folders` **et** `vm.categories` : un dossier fusionné porte un
  identifiant `merged:…` absent des catégories brutes (faux positif sinon).
- Ajout d'un flux local : `PressVM.localFeedAdding` couvre l'enregistrement **et** la première
  synchronisation (bouton désactivé, `done` appelé dans tous les cas) — sans quoi un second appui
  relançait tout. `SubscriptionsScreen` (ajout dans un dossier) ferme sa boîte de dialogue et
  signale par un toast.
- Bandeau de synchro : l'avancement par flux d'un compte (découverte, puis rattrapage) vient de
  `provisionHistory(onFeed)`, relayé par `syncAccount(..., onFeed)` depuis `syncDown`. Le total est
  celui de la phase en cours — les flux en retard ne sont connus qu'après la découverte : le
  compteur est croissant et finit sur le vrai total, jamais sur le nombre d'abonnements.
- Commits auto : `.opencode/plugins/autocommit.js` commit + push tout seul (`git add -A`,
  message `Auto-commit <date>`) à chaque `session.idle`. Un commit manuel avec un vrai message
  reste préférable quand l'utilisateur le demande.
