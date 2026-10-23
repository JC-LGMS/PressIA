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

**Flux RSS locaux : le HTTP en clair est autorisé** (`app/src/main/res/xml/network_security_config.xml`,
`base-config cleartextTrafficPermitted="true"`, référencé par le manifeste). Un flux servi en
HTTP — serveur de test de l'émulateur (`10.0.2.2:8899`), FreshRSS/Miniflux auto-hébergé, blog
encore en http — est donc lu normalement. Le drapeau est dans le fichier de politique réseau
(et non dans un attribut du manifeste) parce qu'Android ne sait pas restreindre cette
permission aux seules adresses privées. Un petit serveur Python maison (`ThreadingHTTPServer`
sur `0.0.0.0:8899`, flux `/fN.xml` + images `/img/N.jpg`) suffit pour les tests : il compte
les requêtes par URL, ce qui vérifie en direct « une seule requête par image » et « tous les
flux interrogés ».

**L'APK release est signé avec la clé debug** (`build.gradle.kts` : `release { signingConfig =
signingConfigs.getByName("debug") }`). Conséquence pratique : un APK **debug** s'installe par
-dessus l'application release avec `adb install -r` **sans perdre les données** (même signature,
même `versionCode`), et devient `run-as`-able → lecture directe de `databases/pressia.db`
(indispensable pour diagnostiquer un flux : `adb exec-out run-as com.pressia cat
databases/pressia.db > copie.db` puis lecture en Python ; pas de `sqlite3` sur l'image de
l'émulateur). À l'inverse, une app **release** installée ne l'est pas (`run-as: package not
debuggable`).

## Flux RSS locaux (pièges)

- **L'identifiant d'un flux local EST son URL.** Un caractère de contrôle collé au clavier (NUL,
  invisible à l'écran) rendait la ligne inutilisable : OkHttp encodait le NUL en `%00`, YouTube
  répondait **404**, et l'app annonçait « flux ajouté et synchronisé » avec **zéro article** —
  l'échec était muet (`fetch` renvoyait une liste vide, `runCatching` avalait l'erreur). Le NUL
  venait de la saisie, pas de PressIA (aucun code ne l'ajoute).
  - `sanitizeFeedUrl` (`data/remote/LocalRssFetcher.kt`) nettoie l'URL **à l'entrée** (retrait
    des caractères de contrôle, ajout de `https://` si le schéma manque) et **à la requête** (un
    flux déjà enregistré reste donc exploitable). `isSupportedFeedUrl` refuse `ftp://` etc.
  - `PressRepository.repairLocalFeeds` (début de `syncDown`) remplace une ligne sale par sa
    version propre : la ligne et ses articles sont supprimés **sur l'identifiant exact** (SQLite
    compare les octets, NUL inclus — Room préserve le NUL dans le binding, il n'y a donc pas de
    troncature), puis le flux est réenregistré proprement. Sans cette réparation, un flux sale
    et son jumeau propre donnaient **deux fois les mêmes articles**.
  - `LocalProvider` ne doit interroger que les flux `provider = "local"` : les abonnements d'un
    compte portent une URL interne (`feed/…`) que le lecteur local ne sait pas interroger (il
    essayait, échouait, et le signalait à chaque synchro).
  - **Le nom du flux remplace l'URL dans les listes.** Un flux saisi à la main n'a pour étiquette
    que son URL — d'où « youtube.com ». `LocalRssFetcher.parseFeed` rend le **titre de la racine**
    (`<feed>` en Atom, `<channel>` en RSS : le nom de la chaîne YouTube, le nom du site), et
    `PressRepository` l'écrit dans `subscriptions.title` (`setFeedTitle`, qui n'écrase que si le
    titre est encore l'URL). Le titre d'une **entrée** ne se confond pas avec celui du flux.
  - **Un flux qui répond 5xx ou 429 est retenté** (une fois, 1,5 s d'attente) : YouTube le fait
    régulièrement, et un 404 — lui — signifie que l'URL est fausse (insister ne sert à rien).
- **Les médias d'une entrée Atom peuvent être imbriqués** : YouTube met sa miniature et sa
  description dans `<media:group>`, hors du parcours des enfants directs → ni vignette ni
  résumé. `parse` parcourt donc toute la descendance (`getAllElements`). `media:content` n'est
  retenu comme image que s'il est **une image** (YouTube y met son lecteur Flash, `type` non
  image) : sans ce filtre la miniature devient une URL de lecteur, non téléchargable.
- **Date : la première trouvée l'emporte.** `published` (publication) passe avant `updated`
  (modification) dans l'ordre du document → c'est la date de publication qui est retenue, pas la
  date de retouche de la vidéo.
- **Un flux local vide doit se dire** : `LocalFeedResult` (VM) distingue « URL refusée »,
  « flux synchronisé (n articles) » et « flux ajouté mais aucun article reçu » ; l'échec HTTP est
  journalisé (`flux local <id> : HTTP 404`).


## Contenu HTML de l'article (texte, liens, vidéos)

- Le contenu affiché vient de `feedHtml(article)` (résumé/contenu du flux, ou copie Readability) puis
  de `articleBlocks(html, baseUrl)` (`ui/ArticleScreen.kt`), qui le découpe en `ABlock` :
  `T` texte, `I` image, `L` lien, `V` vidéo. **Le HTML du flux n'est pas rendu tel quel** : les
  balises sont interprétées bloc par bloc (sélecteur `p, li, h1..h3, pre, blockquote, img, video,
  iframe` + les enfants `a`/`img`/`video`/`iframe` de chaque bloc).
- **Liens** : un `<a href>` devient un bloc `ABlock.L` — texte souligné, couleur primaire, tap →
  `openUrl` (navigateur externe). Le texte du lien est celui de la balise, pas l'URL.
- **Vidéos** : deux cas distincts, à ne pas confondre :
  - `<video src="…mp4">` = **fichier média** → `ABlock.V(embed = false)` : affiche (attribut
    `poster`) cliquable, puis `VideoView` (`AndroidView`) en 16/9, pause sur `ON_STOP`/`ON_DESTROY`,
    arrêt à la sortie. Jamais d'autoplay (forfait, respect du lecteur).
  - `<iframe src="…/embed/…">` = **page web**, pas un média → `ABlock.V(embed = true)` : le lecteur
    intégré ne pourrait pas la lire, le tap ouvre donc la vidéo dans le navigateur. L'affiche est
    l'image YouTube déduite de l'identifiant (`img.youtube.com/vi/<id>/hqdefault.jpg`).
- L'**image AOSP** de l'émulateur n'a **aucun décodeur vidéo** (« no video decoder », « OMX service
  is not available ») : la lecture y échoue alors que le code est correct. Tester la lecture sur un
  appareil réel ou une image `google_apis`.

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
- **La vignette ne se perd pas au retour du serveur.** `upsertArticles` remplace la ligne
  entière : l'`imageUrl` de l'article entrant **écrase** celui qui était en base. Or Feedly ne
  renvoie son champ « visual » que sur les entrées **récentes** : une entrée rejouée sans image
  effaçait celle déjà affichée, et la vignette disparaissait de la liste à chaque
  synchronisation (visible surtout dans le flux ouvert, là où les vignettes se voient).
  `mergeIncomingArticle` conserve donc l'`imageUrl` connu quand l'article entrant n'en porte pas
  (`ArticleFlags.imageUrl` le relit — `flagsOf` le ramène dans la requête).
  - Corollaire : `imageCached` ne vaut pas preuve que le fichier existe. `syncImages` compare
    donc les fichiers au disque (`cachedThumbUrls`) et **retélécharge** les vignettes disparues
    (purge, cache vidé à la main, synchro interrompue) — sans cela la vignette ne revenait
    jamais, l'article étant « déjà téléchargé ».
  - `gcThumbs()` (appelé par `trimToMax` quand des articles dépassent le quota) supprime les
    fichiers dont plus aucun article ne porte l'URL : une `imageUrl` perdue rendait donc la
    vignette orpheline — c'est pour cela que la perte de l'`imageUrl` était si grave.
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
  `local.properties` ou l'environnement. **Feedly n'en a pas besoin** : sa connexion passe par
  la page officielle intégrée (`FeedlySignInScreen`, identifiants saisis sur feedly.com,
  jeton de session récupéré automatiquement) — ne pas conclure qu'un compte distant ne peut
  pas s'authentifier sous prétexte que ces champs sont vides.
- **Identifiants d'application Inoreader (`AppId` / `AppKey`) : ce n'est pas un OAuth, c'est
  l'« authentification applicative »**, exigée par l'API sur *chaque* appel, à côté de
  l'authentification utilisateur (403 sinon). Par défaut ce sont ceux de l'extension
  officielle du navigateur (`build.gradle.kts`) : `1000001524` / `uxILTlPQilTphfcf4xwBEspL_0EDaUZL`
  — publics par construction, identiques dans tous les bundles de l'extension, et suffisants
  pour l'API. `local.properties` ou l'environnement les remplacent (application déclarée par
  le développeur). Ils sont publiés dans `InoreaderAppAuth` et injectés en en-tête par
  l'intercepteur du client Retrofit ; **omis quand ils manquent** (le refus serait le même).
  - Conséquence : le flux **OAuth** Inoreader exige, lui, une application *à soi* (URL de
    redirection `pressia://oauth/inoreader` déclarée) — l'identifiant par défaut n'a pas
    notre redirection, Inoreader refuserait le retour. D'où les champs de l'écran Comptes
    qui démarrent **vides** : c'est à l'utilisateur de saisir les siens.
- **Inoreader : connexion « par session »** (`InoreaderSignInScreen`, comme Feedly) : la page
  `www.inoreader.com` s'ouvre dans une WebView, l'utilisateur s'y connecte, et le **cookie de
  session** est récupéré du bac natif (`CookieManager`) — c'est le mécanisme de l'extension
  officielle Inoreader pour navigateur, transposé en application native. Le cookie porte
  l'identifiant de session (`SID`, protocole `ClientLogin`), que l'API Reader accepte dans
  l'en-tête `Authorization: GoogleLogin auth=…` — le même schéma que le jeton OAuth, donc
  rien à distinguer côté provider. La validation se fait par un **vrai appel API**
  (`user-info`), jamais sur une simple ressemblance : c'est elle qui dit si la session est
  exploitable. Le flux OAuth reste disponible (Comptes → « Connexion OAuth Inoreader »).
  - `AppId` / `AppKey` sont **exigés par l'API sur chaque appel** (403 sinon) : ils sont
    publiés dans `InoreaderAppAuth` et injectés en en-tête par l'intercepteur du client
    Retrofit (`CloudApis`). Les en-têtes sont **ometts quand les identifiants manquent** :
    inutile d'envoyer un en-tête vide, le refus est le même.
  - Candidat de session : `inoreaderSessionCandidates` (pur, testé) tire les valeurs de
    l'en-tête `Cookie`, `SID`/`LSID` d'abord — Inoreader compte les requêtes au quota, on
    n'insiste pas. Le « connexion bloquée ? » ne se déclenche que si le **formulaire de
    connexion a disparu** (`input[type=password]`) sans cookie lisible : sinon il
    s'agirait d'un simple utilisateur en train de saisir ses identifiants.
  - **Identifiants employés, affichables et remplaçables** (Comptes → « Identifiants
    Inoreader » → `InoreaderIdsDialog`) : App ID, App Key et l'en-tête
    `Authorization: GoogleLogin auth=…` du compte connecté. Les valeurs secrètes sont
    **masquées par défaut** (bouton œil). Un identifiant égal à la valeur par défaut est
    stocké **vide** : la valeur par défaut reste ainsi vivante.
    `PressRepository.replaceAccountToken` remplace le jeton **en place** — le compte garde
    son `id`, donc ses dossiers, ses abonnements et ses articles — et **refuse** un jeton
    que le service n'accepte pas ou qui appartient à un autre utilisateur.
  - **Renouvellement du jeton OAuth** : le jeton d'accès expire vite (de l'ordre de l'heure
    chez Inoreader). `OAuth.inoreaderTokens` récupère les **deux** jetons (accès +
    renouvellement) ; le second accompagne le compte dans le registre (`CloudAccount.
    refreshToken`, sérialisé avec le compte — donc couvert par `SECRET_PREFS`).
    `InoreaderProvider.call` enveloppe chaque appel API : sur 401, il renouvelle **une
    seule fois** et rejoue l'appel ; sans cela le compte serait déclaré « session expirée »
    alors qu'un simple renouvellement le ranime. `PressRepository.renewInoreaderToken` fait
    le renouvellement (il est le seul à savoir où lire et réécrire le jeton de
    renouvellement) et **conserve l'identité du compte** : ses dossiers et ses articles
    suivent. Un `CancellationException` n'est **jamais** confondu avec un jeton à
    renouveler.
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
- Tiroir (`MainActivity`) : `gesturesEnabled = drawer.isOpen || when { current == "article" -> false;
  hierarchical -> current != "home"; else -> !(current == "stream" && !atHome) }`.
  Le balayage de bord est coupé là où le geste vers la droite a déjà un sens — article précédent sur
  l'écran article, montée d'un niveau sur l'Accueil et sur une liste de dossier ou de flux
  (`StreamScaffold` / `SubscriptionsScreen`, `onSwipeBack`) — mais **rétabli tiroir ouvert** : c'est
  ce qui permet de le refermer, le `onClose` du scrim Material étant conditionné par `gesturesEnabled`
  (sinon le clic hors du panneau ne fait rien).
- Navigation hiérarchique quand « Tous les articles » est la page d'accueil (`homeRoute == "stream"`,
  `hierarchical` dans `MainActivity`) : racine (« Tous les articles », `stream` sans périmètre)
  → Accueil (`home`) → dossier/flux (`stream` avec périmètre). Le bouton retour et le balayage vers
  la droite remontent **d'un niveau** (`goHome()` : dossier/flux → Accueil, Accueil → racine) ; un
  article revient sur la liste du flux, comme avant. À la racine, le balayage ouvre le tiroir (comme
  sur les autres écrans d'accueil) : c'est `StreamScaffold.onSwipeBack` qui décide, selon
  `hierarchical && atRoot`. En mode « Accueil » classique, `goHome()` ramène tout à la page Accueil,
  qui est alors le dernier niveau.
- Règle générale du retour : **on remonte vers l'accueil configuré, on ne ferme qu'une fois dessus**
  (`atHome` = racine si `hierarchical`, sinon la page `home`). C'est ce qui rend le réglage « Page de
  démarrage » effectif immédiatement : changer l'accueil depuis Réglages ne déplace personne, mais
  le retour suivant depuis Réglages ramène à la dernière page de contenu (`backToLastContent`), puis
  la page courante n'étant plus l'accueil configuré, le retour suivant mène à la **nouvelle** page
  d'accueil, et c'est seulement là qu'un autre retour ferme l'application.
- Balayage de retour : `rememberBackSwipeState()` + `Modifier.backSwipe(state, onSwipe)` et le repère
  `SwipeBackHint` (`StreamScreen.kt`) sont partagés par `StreamScaffold` et `SubscriptionsScreen`.
  Le détecteur est posé sur la boîte qui **enveloppe** la liste : les lignes d'articles, qui ont leur
  propre geste horizontal, le consomment et gardent leur swipe — pour tester le retour, balayer sur
  un **interligne de date** (« Hier », « vendredi 16 octobre 2026 », à ~y=347) ou sur l'espace libre,
  jamais sur une ligne d'article. Seuil 120 px, sinon un frôlement change de page.
- Réglage « Page de démarrage » (`Prefs.HOME_SCREEN`) : `PressVM.homeScreen` démarre à **null**
  (DataStore répond de façon asynchrone) et le contenu de `MainActivity` n'est composé qu'une fois la
  valeur lue (`if (home == null) { Box; return@PressIATheme }`). Sans cette attente, la navigation
  démarrait sur le défaut « Accueil » et le `NavController` **gardait** cette entrée quand le réglage
  arrivait (démarrage erroné, constaté). La route de départ du `NavHost` est ensuite figée
  (`startRoute`, lue une fois) : recréer le graphe / `key(home)` éjectait l'utilisateur de l'écran
  Réglages au moment du choix. La valeur courante, elle, est suivie en direct (`homeRoute`) et pilote
  `hierarchical`, `atHome` et `goHome()` — c'est elle qui rend le changement immédiat (voir la note
  « retour » ci-dessus).
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
