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
- Compose : BOM `2025.03.01` (**1.7.8**), Material3 **1.3.2**. Le `compileSdk 34` est le plafond :
  Compose 1.8+ exige `minCompileSdk=35`, Compose 1.12 exige 37 **et** AGP 9.1. Deux dépendances
  restent épinglées pour la même raison : `material-icons-extended:1.7.8` (plus publiée au-delà)
  et Kotlin/AGP (non touchés lors de la montée Compose).
- tests unitaires dans `app/src/test/java/com/pressia/` (`PressLogicTest.kt`,
  `FeedlyAutoReadTest.kt`, `ReconnectShortcutTest.kt`) ; **aucun androidTest**

## Chaîne d'outils (machine-specific)

`gradle` **n'est pas dans le PATH** et `gradlew.bat` n'est qu'un stub (`gradle %*`) : il n'y a ni
`gradle-wrapper.jar` ni distribution dans `~/.gradle/wrapper/dists`. Le `README.md` dit
`./gradlew`, **ça ne marche pas sur cette machine**. Utiliser les copies présentes dans le dossier
tools temporaire :

| Outil | Chemin |
|---|---|
| JDK 17 | `%LOCALAPPDATA%\Temp\opencode\tools\jdk-17.0.21+9` (la copie `jdk-17.0.20.1+1` n'a plus `javac`, KSP échoue : « does not provide the required capabilities: [JAVA_COMPILER] ») |
| Gradle 8.7 | `%LOCALAPPDATA%\Temp\opencode\tools\gradle-8.7\bin\gradle.bat` |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` (cf. `local.properties`) |
| adb | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` |
| emulator | `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe` (AVD **Pixel_10**) |

Ces outils sont hors du dépôt : s'ils disparaissent, les retélécharger (le ZIP de Gradle 8.7 est
normalement déjà dans le même dossier). **Le dossier temporaire est vidé de temps à autre** (constaté
le 31/10 : seul le JDK restait). Avant de retélécharger Gradle, regarder ce qu'il y a déjà dans le
dépôt de distributions : `~\.gradle\wrapper\dists\gradle-8.10.2-bin\<haché>\gradle-8.10.2\bin\gradle.bat`
— une distribution complète y fonctionne avec AGP 8.5.2.

Préambule PowerShell avant tout appel Gradle :

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Temp\opencode\tools\jdk-17.0.21+9"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$g = "$env:LOCALAPPDATA\Temp\opencode\tools\gradle-8.7\bin\gradle.bat"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
```

`--offline` marche tant que le cache Gradle (`~/.gradle`) est complet. S'il manque un plugin ou une
dépendance de test (`serialization`, `junit`, `org.json`…), relancer **une fois sans** `--offline` :
le cache se complète, puis on revient au hors-ligne.

## Compilation / tests (vérifiés)

```powershell
& $g :app:compileDebugKotlin --console=plain --offline    # vérif rapide après une modif
& $g :app:assembleDebug     --console=plain --offline    # APK debug
& $g :app:testDebugUnitTest --console=plain --offline    # tests unitaires
```

APK produit : `app/build/outputs/apk/debug/app-debug.apk`.

## Version et journal des modifications

- **Le numéro avance tout seul, une fois par évolution.** `app/build.gradle.kts` compare
  l'empreinte de `app/src/main` + du script à `sourceHash` dans `version.properties` : si elle a
  changé, `versionName` passe au correctif suivant (`1.0.x`) et `versionCode` augmente de 1,
  **avant** que `defaultConfig` ne les lise.
  - **Un incrément = une modification terminée, pas une compilation.** Corriger, rebuilder,
    revérifier laisse la version en place : seul le **premier** build d'une évolution
    l'incrémente. Le signal est le commit — le script interroge
    `git diff --quiet HEAD -- version.properties` ; tant que ce fichier porte un incrément non
    commité, la version est conservée (log : « conservée (incrément en attente de commit) »).
    Après le commit, la modification suivante ouvre la version suivante.
  - L'empreinte est réécrite **à chaque** build, même sans incrément : le fichier commité décrit
    exactement les sources commitées, sinon le build suivant incrémenterait à tort.
  - **Ce que couvre l'empreinte** : `app/src/main/**` (Kotlin, `res/`, manifeste…) et
    `app/build.gradle.kts` — pas les tests (`app/src/test`), ni la racine du projet (build.gradle
    racine, wrapper, `local.properties`), ni ce fichier : les toucher ne change rien à la version.
  - Ne jamais retoucher les numéros à la main.
- **Déroulé d'une évolution** (l'ordre compte) :
  1. modifier les sources et compiler autant que nécessaire : le premier build incrémente, les
     suivants conservent ;
  2. écrire l'entrée du journal (voir ci-dessous) ;
  3. **recompiler après la dernière édition** : `version.properties` doit décrire les sources
     telles qu'elles seront commitées, sinon le build suivant les verra changées et ouvrira une
     version de plus, sans rien de neuf derrière ;
  4. installer, vérifier à l'écran, puis commiter sources + journal + `version.properties`
     **ensemble** : un commit = une version.
- **Évolution abandonnée** après l'incrément (tout annulé) : rendre `version.properties` au
  commité (`git restore version.properties`) puis recompiler ; sinon le fichier porte un numéro
  pour un changement qui n'existe pas.
- **Ce que l'application affiche** : « Version installée : 1.0.21 (2026-11-03 00:03) ». Le numéro
  vient du `PackageManager`, la date de `BuildConfig.BUILD_TIME` — donc du **build de l'APK**, pas
  du commit. Un APK construit avant la dernière édition montre une date plus ancienne, et c'est ce
  qui le trahit.
- **Journal des modifications** (`ui/Changelog.kt`, écran `ChangelogScreen`) : ouvert en touchant
  le numéro de version avec sa date de build, en bas du tiroir ou sur l'écran À propos. **À
  chaque commit qui touche l'application, ajouter un `ChangelogItem`** (date, heure du commit,
  titre, détail = le message de commit) **en tête de la version courante** de `changelog()`
  (`Changelog.kt` ; l'historique de 208 commits est à part, dans `changelogHistory()` de
  `ChangelogHistory.kt`, jamais édité) :
  compléter l'entrée existante si la version n'a pas changé, en créer une si le build l'a déjà
  incrémentée. Un commit sans entrée laisse la page mentir sur ce qui a changé. **L'heure est
  celle du commit**, pas celle du build : la noter en dernier, puis recompiler une fois
  (l'incrément en cours est conservé) — deux entrées écrites trop tôt avaient une minute
  d'avance. **Contrôler l'écran après l'installation** (le nombre de changements doit augmenter) :
  un APK produit avant l'édition du journal affiche encore l'ancienne liste, et c'est le seul
  signal.
- **Un commit qui ne touche ni `app/src/main` ni `app/build.gradle.kts`** (tests, documentation,
  outillage — ce fichier compris) **ne change ni la version ni le journal** : l'empreinte ne
  couvre que ces sources, et la page ne consigne que ce qui est livré dans l'APK.
- L'historique initial (208 commits, jusqu'au 2026-10-25) est dans `ui/ChangelogHistory.kt`,
  généré depuis `git log` : **ne plus le modifier**. Seuls les commits qui touchent `app/src/main`
  ou `app/build.gradle.kts` y figurent (ni tests, ni documentation, ni outillage).

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
L'ordre de grandeur du dump identifie l'écran : menu contextuel (DropdownMenu) ≈ 4 KB, liste
d'articles 35–45 KB, détail d'article (WebView) 45–50 KB.

Pour **chronométrer** une animation, `uiautomator dump` est inadapté : il attend l'inactivité de
l'interface (2–4 s). Utiliser `screencap -p /sdcard/x.png` + `pull` (~400–700 ms par capture, à
soustraire du temps mesuré) ou une trace `logcat` temporaire, retirée avant le commit.

**La pile de navigation n'est pas un état d'écran.** `nav.currentBackStack` est un `StateFlow`
depuis Navigation 2.7 (lire `.value`) ; les arguments d'écran y sont conservés par entrée, ce qui
sert à vérifier un parcours retour (voir `contentReturnTarget` et `ARTICLE_ID`).

### Simuler un appui long (long-press)

`input swipe X Y X Y DURATION` (même départ et arrivée) est parfois pris pour un simple tap.

1. **`input motionevent DOWN` / `UP`** — le plus fiable :

   ```powershell
   & $adb -s emulator-5554 shell "input motionevent DOWN 540 473"
   Start-Sleep -Seconds 2
   & $adb -s emulator-5554 shell "input motionevent UP 540 473"
   ```

2. **Warm-up obligatoire** après `am force-stop` : le ComposeView n'enregistre pas les gestes
   longs tout de suite. Faire un `input tap` + `KEYCODE_BACK` **avant** de tenter le long-press :

   ```powershell
   & $adb -s emulator-5554 shell "input tap 540 473"            # warm-up
   Start-Sleep -Seconds 2
   & $adb -s emulator-5554 shell input keyevent KEYCODE_BACK    # retour liste
   Start-Sleep -Seconds 2
   & $adb -s emulator-5554 shell "input swipe 540 473 540 473 3000"
   ```

3. Relancer sans `force-stop` avec `monkey -p com.pressia -c android.intent.category.LAUNCHER 1`
   (plus rapide, le warm-up est déjà fait si l'app était au premier plan).

**Touches dans une WebView : viser ~90 px plus bas.** `input tap` atterrit environ 90 px trop
haut dans le contenu web (constaté : le bouton gris « Sign in with Google » se prenait pour le vert
« Sign in with Email » juste en dessous). La WebView n'expose aucun node (opaque pour
uiautomator) : passer par capture d'écran (`screencap` + `read` du PNG, 1:1 avec l'écran
1080×2424) et compenser `tap(X, Y+90)`. Champs hauts tolérants ; boutons ~80 px : rater = page
voisine. Revenir par la flèche retour de l'app, jamais `KEYCODE_BACK` dans une page web (il risque
de quitter l'écran au lieu de fermer le clavier) ; pour replier le clavier, toucher une zone
neutre.

**Flux RSS locaux : HTTP en clair autorisé** (`app/src/main/res/xml/network_security_config.xml`,
`base-config cleartextTrafficPermitted="true"`, référencé par le manifeste). Un flux servi en HTTP
— serveur de test de l'émulateur (`10.0.2.2:8899`), FreshRSS/Miniflux auto-hébergé, blog encore en
http — est donc lu normalement. Le drapeau est dans le fichier de politique réseau (et non dans un
attribut du manifeste) parce qu'Android ne sait pas restreindre cette permission aux seules adresses
privées. Un petit serveur Python maison (`ThreadingHTTPServer` sur `0.0.0.0:8899`, flux `/fN.xml`
+ images `/img/N.jpg`) suffit pour les tests : il compte les requêtes par URL, ce qui vérifie en
direct « une seule requête par image » et « tous les flux interrogés ».

**L'APK release est signé avec la clé debug** (`build.gradle.kts` : `release { signingConfig =
signingConfigs.getByName("debug") }`). Conséquence pratique : un APK **debug** s'installe par-dessus
l'application release avec `adb install -r` **sans perdre les données** (même signature), et devient
`run-as`-able → lecture directe de `databases/pressia.db` (`adb exec-out run-as com.pressia cat
databases/pressia.db > copie.db` puis lecture en Python ; pas de `sqlite3` sur l'image de
l'émulateur). Une app **release** installée, elle, ne l'est pas (`run-as: package not debuggable`).

## Flux RSS locaux (pièges)

- **L'identifiant d'un flux local EST son URL.** Un caractère de contrôle collé au clavier (NUL,
  invisible à l'écran) rendait la ligne inutilisable : OkHttp l'encodait en `%00`, YouTube
  répondait **404**, et l'app annonçait « flux ajouté et synchronisé » avec **zéro article** —
  l'échec était muet (`fetch` renvoyait une liste vide, `runCatching` avalait l'erreur).
  `sanitizeFeedUrl` (`data/remote/LocalRssFetcher.kt`) nettoie l'URL **à l'entrée** (retrait des
  caractères de contrôle, ajout de `https://` si le schéma manque) et **à la requête** (un flux
  déjà enregistré reste donc exploitable) ; `isSupportedFeedUrl` refuse `ftp://` etc.
  `PressRepository.repairLocalFeeds` (début de `syncDown`) remplace une ligne sale par sa version
  propre : la ligne et ses articles sont supprimés **sur l'identifiant exact** (SQLite compare les
  octets, NUL inclus — Room préserve le NUL dans le binding, il n'y a pas de troncature), puis le
  flux est réenregistré. Sans cette réparation, un flux sale et son jumeau propre donnaient
  **deux fois les mêmes articles**.
- `LocalProvider` ne doit interroger que les flux `provider = "local"` : les abonnements d'un
  compte portent une URL interne (`feed/…`) que le lecteur local ne sait pas interroger (il
  essayait, échouait, et le signalait à chaque synchro).
- **Pas de plafond sur le nombre de flux locaux** : `LocalProvider.stream` les interroge tous, en
  parallèle bornée (8 à la fois). Un `take(n)` en laissait silencieusement une partie sans
  articles (constaté avec 32 flux : 30 servis seulement).
- **Le nom du flux remplace l'URL dans les listes.** Un flux saisi à la main n'a pour étiquette
  que son URL — d'où « youtube.com ». `LocalRssFetcher.parseFeed` rend le **titre de la racine**
  (`<feed>` en Atom, `<channel>` en RSS : le nom de la chaîne YouTube, le nom du site), et
  `PressRepository` l'écrit dans `subscriptions.title` (`setFeedTitle`, qui n'écrase que si le
  titre est encore l'URL). Le titre d'une **entrée** ne se confond pas avec celui du flux.
- **Un flux qui répond 5xx ou 429 est retenté** (une fois, 1,5 s d'attente) : YouTube le fait
  régulièrement, et un 404 — lui — signifie que l'URL est fausse (insister ne sert à rien).
- **Les médias d'une entrée Atom peuvent être imbriqués** : YouTube met sa miniature et sa
  description dans `<media:group>`, hors du parcours des enfants directs → ni vignette ni résumé.
  `parse` parcourt donc toute la descendance (`getAllElements`). `media:content` n'est retenu comme
  image que s'il est **une image** (YouTube y met son lecteur Flash, `type` non image) : sans ce
  filtre la miniature devient une URL de lecteur, non téléchargable.
- **Date : la première trouvée l'emporte.** `published` (publication) passe avant `updated`
  (modification) dans l'ordre du document → c'est la date de publication qui est retenue.
- **Un flux local vide doit se dire** : `LocalFeedResult` (VM) distingue « URL refusée », « flux
  synchronisé (n articles) » et « flux ajouté mais aucun article reçu » ; l'échec HTTP est
  journalisé (`flux local <id> : HTTP 404`). La suppression d'un flux local est décrite plus bas
  (`deleteLocalFeed`).
- **Un corps de flux ou une page d'article est lu sous plafond** (`readCapped`, dans
  `data/Bytes.kt` ; même helper pour les miniatures) : 5 Mo pour un flux (`LocalRssFetcher.MAX_FEED_BYTES`), 8 Mo pour une
  page d'article (`Readability.MAX_PAGE_BYTES`). Huit flux sont interrogés en parallèle, et une
  page peut être énorme (archive, réponse sans fin, URL hostile) : sans plafond, la synchro
  mourait en `OutOfMemoryError`. Un dépassement est un **échec de récupération** (erreur
  journalisée côté flux local, `FullResult.Failed` côté Readability). Le corps est décodé avec
  le charset du `Content-Type`, jamais par `body.string()`, qui lit tout. `readCapped` lit
  jusqu'au bout d'un flux plus petit que le plafond (il ne s'arrête qu'au-delà) et ne connaît pas
  la taille à l'avance : quand le serveur l'annonce, `exceedsCap(body.contentLength(), …)` échoue
  **avant** la lecture, aux trois appelants (miniature, flux, page).

## Contenu HTML de l'article (texte, liens, vidéos)

- Le contenu affiché vient de `feedHtml(article)` (résumé/contenu du flux, ou copie Readability)
  puis de `articleBlocks(html, baseUrl)` (`ui/ArticleScreen.kt`), qui le découpe en `ABlock` :
  `T` texte, `I` image, `L` lien isolé, `V` vidéo, `Table` tableau. **Le HTML du flux n'est pas
  rendu tel quel** : les balises sont interprétées bloc par bloc (sélecteur `ARTICLE_SELECTOR` :
  `p, li, h1..h6, pre, blockquote, figcaption`, plus `img, video, iframe, table`).
- **Chaque bloc de texte a un genre** (`ABlock.Kind`) qui pilote son rendu : `Title1/2/3`
  (`h1`, `h2`, `h3..h6`), `Bullet` (`li`, puce), `Quote` (`blockquote`, italique), `Caption`
  (`figcaption`, petit et centré), `Body` sinon. Un sous-bloc garde le genre du parent (un `p`
  dans un `li` reste une puce) sauf si sa balise en impose un (un `h3` dans une citation est un
  titre). Avant, tout était rendu en paragraphe : un titre avait la taille d'une phrase, et un
  `figcaption` ou un `table` disparaissait entièrement.
- **Gras, italique, surlignage, soulignement et barré sont des plages du texte**
  (`ABlock.Span` : `bold`, `italic`, `highlight` pour `<mark>`, `underline`, `strike`) : `strong`/`b`,
  `em`/`i`/`cite`, `mark`, `u`/`ins`, `s`/`del`/`strike` ne coupent pas la phrase, ils s'ajoutent
  aux annotations de `blockText` (avec les liens) — le surlignage prend `primaryContainer` en fond,
  qui suit le thème et le fond choisi. `isUnderlined` / `isStruck` reconnaissent aussi le style en
  ligne (`text-decoration: underline` / `line-through`, fréquent dans les flux ; Readability retire
  les styles, pas les balises) ; les deux traits se combinent (`TextDecoration.combine`). Le style
  en cours est porté par `Inline` et copié à chaque élément rencontré, si bien que les plages ne se
  chevauchent pas.
- **Un `table` devient `ABlock.Table(head, rows)`** : `th` de la première ligne en en-tête (gras,
  fond plus marqué), lignes suivantes sur fond alterné, filets et colonnes de largeur égale. Les
  fonds sont dérivés de `onSurface` en transparence (et non de `surfaceVariant`, qui vaut le fond
  quand une couleur de fond est choisie). Ses cellules ne sont pas traitées comme des blocs à part
  (`isNestedBlock` écarte tout ce qui est sous un `table`).
- **Une `iframe` n'est pas forcément une vidéo** : seuls YouTube (toutes ses écritures) et les
  hôtes de lecteur connus (`isVideoEmbed` : Vimeo, Dailymotion, Twitch) donnent un
  `ABlock.V(embed = true)`. Toute autre iframe — carte d'un autre article
  (`wp-embedded-content` chez MiniMachines), formulaire, commentaires — devient un `ABlock.L`
  (son attribut `title`, à défaut l'hôte) : elle affichait sinon un bloc lecteur noir pour une
  page qui n'est pas une vidéo.
- **Les liens restent dans le flux du texte.** Un conteneur (`p`, `li`, `h1..h3`, `pre`,
  `blockquote`) est coulé **d'un seul tenant** : ses liens ne sont pas des blocs à part (ils
  passaient à la ligne au milieu d'une phrase) mais des plages cliquables d'un `ABlock.T`
  (`ABlock.Link(start, end, url)`), rendues par `blockText` en annotations de lien — couleur
  primaire, soulignées — dont le tap ouvre le navigateur externe. Seul un lien **hors** de tout
  conteneur reste un `ABlock.L`. Pièges de la construction : le texte vient de `wholeText` (et non
  de `text`, que jsoup rogne et qui souderait les mots de part et d'autre d'un lien) ; un seul
  blanc au raccord entre morceaux ; les éléments en ligne (`strong`, `em`, `span`…) sont parcourus
  récursivement (leur texte était perdu) mais un **sous-bloc** (`p` dans `li`, deux `p` dans une
  citation) reste un bloc à part ; un conteneur imbriqué dans un autre n'est pas sélectionné deux
  fois (`isNestedBlock`) ; les médias coupent le texte (ce qui précède est publié, le média prend
  sa place, la suite reprend) ; l'URL d'un lien n'entre pas dans `seen`, sinon le second passage
  du même lien amputerait la phrase.
- **Texte sélectionnable** : la colonne défilante de l'écran article est enveloppée dans un
  `SelectionContainer` (appui long, poignées, « Copier / Tout sélectionner »). Le balayage
  horizontal d'article continue de fonctionner : c'est le geste immédiat, la sélection demandant
  un appui long.
  Les deux actions de lecture — « Rechercher sur Internet » et « Traduire » — viennent d'un
  `TextToolbar` propre à l'écran (`ArticleTextToolbar`, posé dans `LocalTextToolbar` autour du
  `SelectionContainer`) : Compose 1.7 ne livre pas le texte sélectionné à la barre, si bien que
  chaque action **copie** la sélection (même rappel que « Copier ») puis relit le presse-papiers
  (`selectionText`). Ne pas chercher une API de sélection à la place : il n'y en a pas dans cette
  version ; monter de Compose la rendrait disponible (`TextToolbarState`).
- **Vidéos** : deux cas distincts, à ne pas confondre :
  - `<video src="…mp4">` = **fichier média** → `ABlock.V(embed = false)` : affiche (attribut
    `poster`) cliquable, puis `VideoView` (`AndroidView`) en 16/9, pause sur
    `ON_STOP`/`ON_DESTROY`, arrêt à la sortie. Jamais d'autoplay (forfait, respect du lecteur).
    **La lecture démarre dans `onPrepared`, jamais juste après `setVideoURI`** : avant la
    préparation, `start()` ne fait rien et la lecture ne revient plus (constaté : bloc noir, ni
    son ni image, aucun journal `MediaPlayer`). Le `VideoView` doit aussi être créé **dans** la
    fenêtre (`factory` d'`AndroidView`), pas par un `remember` détaché.
  - `<iframe src="…/embed/…">` = **page web**, pas un média → `ABlock.V(embed = true)`. Pour
    **YouTube**, la vidéo est lue **dans l'application** (`YoutubePlayer`), sur la page
    d'**intégration** (`youtubeEmbedUrl` → `youtube.com/embed/<id>`), chargée avec un
    **User-Agent de navigateur** (`youtubeBrowserUserAgent`) : YouTube y sert la vraie page
    mobile, dont les commandes sont de **vrais éléments du document** (titre, son, sous-titres,
    roue, ⤢, barre d'avancée, temps) — il n'y a donc rien à poser à la place.
    La page de **lecture** `m.youtube.com/watch` a été essayée puis abandonnée : en WebView `wv`
    elle n'offre aucun contrôle utilisable (boutons peints sur un canevas, hors de portée des
    appuis), et surtout **elle se met en pause toute seule** quelques secondes après le début —
    pile relevée : `onVideoProgress` → `visibility.Xj()` → `pauseVideo` —, y compris avec un
    User-Agent de navigateur et sans feuille de style de l'application. Le lecteur du site, lui,
    joue normalement (aucune pause spontanée constatée).
    **`Referer` = `https://m.youtube.com/`** (`youtubeReferer`) : un `loadUrl` sans en-tête n'en
    envoie aucun (« Erreur 152 »), et `www.youtube.com` comme référent d'une intégration est
    refusé (une page YouTube ne peut pas en intégrer une autre) — vérifié en direct ; l'erreur
    s'affiche **dans** le bloc. La page est **habillée** par `youtubePlayerScript(youtubeSiteCss())` :
    géométrie du bloc 16/9, fond noir, pas de défilement. Ne pas réintroduire de détection
    d'erreur par lecture du texte de la page : elle se déclenchait à tort, basculait sur l'affiche
    et le tap ouvrait l'application YouTube installée. Les autres services d'intégration (Vimeo,
    Dailymotion…) restent ouverts dans le navigateur. L'affiche est l'image YouTube déduite de
    l'identifiant (`img.youtube.com/vi/<id>/hqdefault.jpg`).
    **Rien ne se charge avant un appui** : l'affiche occupe le bloc (`VideoPoster`), et le tap
    seul crée la WebView. La page d'intégration n'a pas d'autoplay : c'est `youtubeSiteScript`
    qui démarre la lecture (geste de l'utilisateur, `mediaPlaybackRequiresUserGesture = false`
    aidant) et rétablit le son (`unMute`), YouTube démarrant muet sans geste. Le délai de secours
    de 20 s ne court qu'à partir du tap.
    Deux mesures : `touch-action:none` (`youtubeSiteCss`) évite que Chromium prenne un glissement
    horizontal pour un défilement et l'annule (`pointercancel` au bout de ~15 px) ; et un doigt
    posé sur le lecteur sort du balayage d'article (`detectArticleSwipe`, repère `playerGesture`)
    — sinon tirer la barre d'avancée changeait d'article.
    **Titre sous le lecteur** : `YoutubeTitleRow` l'affiche et l'ouvre dans YouTube ; il vient
    d'oEmbed (`youtubeTitle`, sans clé d'API, mis en cache dans `youtubeTitles`, lui-même borné à
    200 titres en LRU : une session qui parcourt des dizaines de vidéos n'accumule pas un titre
    par vidéo pour la vie du processus). La WebView est
    mise en pause avec l'écran (`onPause`/`onResume` sur `ON_PAUSE`/`ON_RESUME`) : sans cela le
    son continuait après un changement d'article.
  - **La touche Retour doit être rendue à l'application.** Quand la vidéo joue, l'appui est
    **consommé par la hiérarchie de vues** : ni la WebView, ni la vue plein écran, ni le
    `BackHandler` de l'écran, ni `onBackPressed` ne le reçoivent (constaté : `Activity.dispatchKeyEvent`
    le voit passer, `onBackPressed` n'est jamais appelé ; le même appui, vidéo en pause, suivait le
    chemin normal). Deux garde-fous, parce que le chemin dépend de qui a la touche :
    - `PlayerWebView` (la sous-classe de WebView que le lecteur utilise) intercepte `KEYCODE_BACK`
      **avant Chromium** et le rend au `OnBackPressedDispatcher` : sinon Chromium l'avale et
      **arrête la lecture** (`crMediaCodecBridge: Codec released`) au lieu de quitter l'écran ;
    - `PlayerBack.handler` : tant que le lecteur est affiché, `MainActivity.dispatchKeyEvent`
      confie la touche au gestionnaire de l'écran article — plein écran d'abord, puis
      `OnBackPressedDispatcher.onBackPressed()`, exactement le comportement habituel — et la
      consomme. Hors du lecteur, le gestionnaire vaut `null` et rien n'est intercepté.
    Vérifié dans les quatre cas : vidéo en lecture, en pause, plein écran, et retour **après** un
    aller-retour en plein écran.
  - **Le plein écran est celui de l'application** (`PlayerFullscreen` + `PlayerJsBridge`) : l'API
    Fullscreen de la page est refusée par la WebView. La WebView est **déplacée** dans un
    `FrameLayout` posé sur la racine de la fenêtre — même page, même instant de lecture ; la
    recréer relancerait la vidéo au début. La classe `pressia-full` étire l'image sur toute la
    hauteur, l'activité passe en paysage et les barres système s'effacent ; ✕ en haut à gauche, et
    la vue plein écran est libérée dans l'`onRelease` de l'`AndroidView` (`screen.release()`, qui
    couvre aussi la sortie de composition). Déplacer la vue coupe le média :
    `pressiaResume` est rappelé au retour (150/600/1500/3000 ms).
    **Un aller-retour en plein écran se voit à l'écran**, et se vérifie en trois points : la classe
    `pressia-full` et `window.pressiaFull` (page), l'icône du bouton sous la vidéo (agrandir →
    réduire, `FullscreenToggle`), et le ✕ posé par l'application (uiautomator le liste sous
    « quitter le plein écran »). Attention à la géométrie des appuis : en paysage la fenêtre
    commence ~141 px à droite du bord (encoche), et 1 px CSS vaut 2,625 px d'écran — viser le
    centre du bounds mesuré dans la page, jamais des coordonnées devinées.
    `PlayerFullscreen.enter` rend un **booléen** : si le déplacement échoue (vue détachée),
    l'appelant remet l'état à « réduit », sans quoi le bouton restait dans le mauvais sens.
  - **Écran noir à l'appui** : la WebView était peinte en noir (`setBackgroundColor(BLACK)`)
    avant d'avoir la taille de son bloc, ce qui noircissait **tout** l'écran une seconde. Fond
    transparent, noir dû à la boîte qui l'entoure (`clipToBounds`) et à la page.
- Une image **AOSP** d'émulateur n'a **aucun décodeur vidéo** (« no video decoder », « OMX service
  is not available ») : la lecture y échoue alors que le code est correct. L'AVD `Pixel_10` est une
  image `google_apis_playstore` ; en cas de doute, tester sur un appareil réel. L'image peut y
  manquer alors que le son passe : mesurer par **statistiques de pixels** (`shot.py` : moyenne,
  écart-type, mouvement sur deux captures) plutôt qu'en lisant une capture — un lecteur noir donne
  0 % de mouvement, une vidéo qui joue en donne plusieurs dizaines.

## Images / miniatures (non intuitif, tout est là)

- Miniature **déclarée** par le flux (`imageUrl`, bannière de liste et image de tête) :
  `ThumbStore` (`data/local/ThumbStore.kt`), un fichier `SHA-1(url).jpg` dans `filesDir/thumbs`.
  Gabarit = **carré de la largeur d'écran** : c'est la plus grande taille à laquelle ce fichier est
  affiché (image de tête d'article, pleine largeur ; la bannière magazine de 180 dp en est un
  recadrage). Toute image plus large que haute est donc enregistrée à la largeur exacte de l'écran
  (2400×1600 → 1080×720), jamais l'original full-size, jamais d'agrandissement à l'affichage. Une
  URL partagée ne se télécharge qu'une fois.
- Images **du corps** : le cache disque de Coil, par préchargement — même mécanisme que les
  favicons (`ImageRequest` + `execute`, `memoryCachePolicy(DISABLED)` et `size(1,1)` : c'est le
  cache disque qui compte, pas le décodage). Trop nombreuses pour un fichier dédié au gabarit.
  `Coil.setImageLoader` pose `respectCacheHeaders(false)`, donc elles sont rangées sous leur URL et
  l'affichage les sert sans réseau (`thumbModel()`, URL → cache Coil). Corollaire :
  `refreshFavicons()` ne vide **plus** tout le cache disque (il retire les seules entrées de
  favicons, `purgeImages`) — « Actualiser favicons » jetait sinon les images préchargées,
  définitivement (`imageCached` reste à 1).
- Suivi : `ArticleEntity.imageCached` = « toutes les images de cet article sont là ». `syncImages()`
  (`PressRepository`, sous `imagesMutex`) prend les articles à `imageCached = 0` (lus + non lus, 6
  en parallèle, inactif si « Images hors-ligne » coupé ou « Wi-Fi uniquement » hors Wi-Fi), liste
  leurs images (`articleImageUrls` : miniature déclarée puis `<img>` de `content`/`summary`,
  dédoublonnées), met la miniature dans `ThumbStore` et les autres via Coil, puis marque
  l'article — une image manquante le laisse en attente (elle sera reprise ; marquage par article,
  pas par URL). Appelé en fin de `syncDown` (`withThumbs = true`) et de `backfill` : **toute** sync
  (bouton, lancement, worker, connexion de compte) récupère les images.
- **Un échec d'image ne fait pas échouer la synchronisation.** `syncImages` relève les flux dont un
  article garde une image manquante (timeout, 404…) dans `imageFailures` ; le pipeline et l'ajout
  d'un flux passent par `syncImagesSafely` (erreur inattendue journalisée, `imagesInterrupted`).
  `LAST_SYNC_AT` avance dès que les articles et le reste sont passés, et `imageFailureNote` ajoute
  au message final « Images non récupérées : Flux A, Flux B » (quatre noms, puis « et N autres
  flux »). La projection `articlesPendingImages` porte `feedId`/`feedTitle` pour pouvoir les nommer.
- **Ne jamais** relancer une passe depuis `syncReadLater` : le pipeline en lance déjà une en
  parallèle (`supervisorScope`), et deux passes simultanées lisaient la même liste d'articles et
  téléchargeaient deux fois les mêmes images (constaté : 26 images sur 32 demandées deux fois).
- **La vignette ne se perd pas au retour du serveur.** `upsertArticles` remplace la ligne entière :
  l'`imageUrl` de l'article entrant **écrase** celui qui était en base. Or Feedly ne renvoie son
  champ « visual » que sur les entrées **récentes** : une entrée rejouée sans image effaçait celle
  déjà affichée, et la vignette disparaissait de la liste à chaque synchronisation.
  `mergeIncomingArticle` conserve donc l'`imageUrl` connu quand l'article entrant n'en porte pas
  (`ArticleFlags.imageUrl` le relit — `flagsOf` le ramène dans la requête).
  - Corollaire : `imageCached` ne vaut pas preuve que le fichier existe. `syncImages` compare donc
    les fichiers au disque (`cachedThumbUrls`) et **retélécharge** les vignettes disparues (purge,
    cache vidé à la main, synchro interrompue) — sans cela la vignette ne revenait jamais, l'article
    étant « déjà téléchargé ».
  - `gcThumbs()` (appelé par `trimToMax` quand des articles dépassent le quota) supprime les
    fichiers dont plus aucun article ne porte l'URL : une `imageUrl` perdue rendait donc la
    vignette orpheline — c'est pour cela que la perte de l'`imageUrl` était si grave.
- `clearCache()` / `clearImages()` effacent les fichiers **et** remettent `imageCached` à 0 : la
  prochaine sync, quelle qu'elle soit, récupère tout. Ils purgent aussi les HTML extraits via
  Readability (`content = summary`, `fullFetched = 0`) : les articles sont conservés avec leur
  extrait RSS, la prochaine ouverture complète ré-extrait.
- Listes : les 3 `AsyncImage` d'`ArticleRow` passent par `thumbModel()` (fichier local si présent,
  sinon URL paresseuse au scroll — aucun préchargement de liste). **L'écran article aussi**
  (bannière et images du corps), par le même `thumbModel()`. Une URL déjà téléchargée à la synchro
  ne repart donc pas sur le réseau : fichier `ThumbStore` pour `imageUrl` — et pour la première
  image du corps de tout flux sans `enclosure`/`media:*`, puisque `LocalRssFetcher` prend alors la
  première `<img>` de la description (la miniature **est** la première image de l'article) —, cache
  disque Coil pour les autres. Vérifié en direct : une requête par image à la synchro, **zéro** à
  l'ouverture. Reste : la visionneuse (appui) garde l'URL d'origine, c'est là qu'on récupère la
  pleine qualité.
- `isSameImage` compare les noms de fichier **après nettoyage des variantes CDN**
  (`…-1024x495.webp`, `…__800_800__overflow.jpg`) : sans ça la vignette du flux (`imageUrl`)
  s'affichait en plus de la même image déjà présente dans le contenu (Les Numériques, Next).

## Réglages et préférences

- Réglage ajouté, modifié ou supprimé = cycle complet obligatoire : clé dans `Prefs`, état +
  `reloadFromPrefs`/`save()` dans l'écran Réglages, entrée dans `Prefs.ENTRIES` (sinon absent de
  l'export/import — test `entrees de preferences` à étendre), valeur par défaut cohérente partout
  où elle est lue.
- Les sections sont posées par `SettingsSection(titre)` (`ExtraScreens.kt`, filet + titre coloré) —
  un nouveau réglage rejoint la section de son usage.
- **Chaque réglage est encadré** (`SettingBox`, fin liseré `outlineVariant`) avec son libellé, son
  option et son explication : la page est une longue suite de lignes, sans bordure un interrupteur
  se lisait avec le paragraphe voisin. Une note qui vaut pour toute la section (volume et
  fréquence) reste **hors** encadré, juste sous le titre de section.

## Pièges connus — réseau, comptes et synchronisation

- Les champs de config OAuth (`FEEDLY_CLIENT_ID`, `INOREADER_APP_ID`, …) sont lus dans
  `local.properties` ou l'environnement. **Feedly n'en a pas besoin** : sa connexion passe par la
  page officielle intégrée (`FeedlySignInScreen`, identifiants saisis sur feedly.com, jeton de
  session récupéré automatiquement) — ne pas conclure qu'un compte distant ne peut pas
  s'authentifier sous prétexte que ces champs sont vides.
- **Identifiants d'application Inoreader (`AppId` / `AppKey`) : ce n'est pas un OAuth, c'est
  l'« authentification applicative »**, exigée par l'API sur *chaque* appel, à côté de
  l'authentification utilisateur (403 sinon). Par défaut ce sont ceux de l'extension officielle du
  navigateur (`build.gradle.kts`) : `1000001524` / `uxILTlPQilTphfcf4xwBEspL_0EDaUZL` — publics
  par construction, identiques dans tous les bundles de l'extension, et suffisants pour l'API.
  `local.properties` ou l'environnement les remplacent (application déclarée par le développeur).
  Ils sont publiés dans `InoreaderAppAuth` et injectés en en-tête par l'intercepteur du client
  Retrofit (`CloudApis`), **omis quand ils manquent** (le refus serait le même).
  - Conséquence : le flux **OAuth** Inoreader exige, lui, une application *à soi* (URL de
    redirection `pressia://oauth/inoreader` déclarée) — l'identifiant par défaut n'a pas notre
    redirection, Inoreader refuserait le retour. D'où les champs de l'écran Comptes qui démarrent
    **vides** : c'est à l'utilisateur de saisir les siens.
- **Inoreader : connexion « par session »** (`InoreaderSignInScreen`, comme Feedly) : la page
  `www.inoreader.com` s'ouvre dans une WebView, l'utilisateur s'y connecte, et l'en-tête `Cookie`
  **que le navigateur enverrait à l'API** est lu (`CookieManager.getCookie(<url API>)` : seuls les
  cookies du domaine et du chemin, `HttpOnly` compris — `document.cookie` ne les montre pas).
  C'est le mécanisme de l'extension officielle Inoreader pour navigateur, transposé en application
  native ; la connexion est **automatique** dès que le cookie apparaît (sonde de 1,2 s, essai
  au plus toutes les 4 s), les boutons « Je suis connecté » / « Réessayer » ne font que forcer.
  - Le cookie **porteur est `al`** (domaine `.www.inoreader.com`, `HttpOnly`) — pas `SID`.
    Vérifié en direct : `al` seul authentifie `reader/api/0/user-info` (200), `ssid` seul est
    refusé (401). `ssid` est un cookie de **session** (non persistant) : c'est le texte de l'en-tête
    stocké dans le compte qui garde la session utilisable après fermeture de l'application ; il
    finit par expirer, d'où la reconnexion.
  - L'API est appelée **comme l'extension le fait** : en-tête `Cookie` complet, `AppId`/`AppKey` à
    côté (intercepteur), **sans** en-tête `Authorization`. `inoreaderAuth` (pur, testé) classe le
    jeton du compte : `InoreaderAuth.WebCookie` (préfixe `cookie:`, ou forme d'un en-tête `Cookie`)
    → `Cookie: al=…`, `InoreaderAuth.OAuth` → `Authorization: GoogleLogin auth=…`.
    `InoreaderProvider.headers` fait la traduction et `InoreaderService` prend un `@HeaderMap` (les
    deux formes passent par le même client).
  - Candidats : `inoreaderCookieCandidates` (pur, testé) rend l'en-tête complet puis la vue réduite
    aux cookies de session (`al`, `ssid`, `bsid`), `al` en tête — Inoreader compte les requêtes au
    quota. La validation se fait par un **vrai appel API** (`user-info`), jamais sur une simple
    ressemblance. `inoreaderCookieHasSession` évite de lancer un appel (compté) tant que le bac ne
    porte aucun cookie de session. Le « connexion bloquée ? » ne se déclenche que si le
    **formulaire de connexion a disparu** (`input[type=password]`) sans cookie lisible : sinon il
    s'agirait d'un utilisateur en train de saisir ses identifiants. Le flux OAuth reste disponible
    (Comptes → « Connexion OAuth Inoreader »).
  - **Identifiants employés, affichables et remplaçables** (Comptes → « Identifiants Inoreader » →
    `InoreaderIdsDialog`) : App ID, App Key et l'AUTH du compte connecté — `Authorization:
    GoogleLogin auth=…` pour un compte OAuth, `Cookie: al=…` pour une session web (le libellé du
    champ et l'en-tête affiché suivent la forme reconnue par `inoreaderAuth`). Les valeurs secrètes
    sont **masquées par défaut** (bouton œil). Un identifiant
    égal à la valeur par défaut est stocké **vide** : la valeur par défaut reste ainsi vivante.
    `PressRepository.replaceAccountToken` remplace le jeton **en place** — le compte garde son
    `id`, donc ses dossiers, ses abonnements et ses articles — et **refuse** un jeton que le
    service n'accepte pas ou qui appartient à un autre utilisateur.
  - **Renouvellement du jeton OAuth** : le jeton d'accès expire vite (de l'ordre de l'heure chez
    Inoreader). `OAuth.inoreaderTokens` récupère les **deux** jetons (accès + renouvellement) ; le
    second accompagne le compte dans le registre (`CloudAccount.refreshToken`, sérialisé avec le
    compte — donc couvert par `SECRET_PREFS`). `InoreaderProvider.call` enveloppe chaque appel
    API : sur 401, il renouvelle **une seule fois** et rejoue l'appel ; sans cela le compte serait
    déclaré « session expirée » alors qu'un simple renouvellement le ranime.
    `PressRepository.renewInoreaderToken` fait le renouvellement (il est le seul à savoir où lire
    et réécrire le jeton de renouvellement) et **conserve l'identité du compte** : ses dossiers et
    ses articles suivent. Un `CancellationException` n'est **jamais** confondu avec un jeton à
    renouveler.
  - **Le jeton CSRF (`api.token`) vaut pour la session** : `InoreaderProvider.tag` le demande une
    fois par lot (`markRead` / `markUnread`), pas par article — sinon chaque article coûtait deux
    requêtes (GET token + POST edit-tag).
- **Tables JSON de DataStore** (sessions expirées, empreintes « read later », totaux de
  réconciliation) : lecture-modification-écriture, donc **jamais sans verrou** (`expiredMutex`,
  `hashMutex`). Les comptes se synchronisent en parallèle : sans lui, deux écritures qui se
  chevauchent perdent la première (préférence disparue).
- File montante : `pendingSync()` est une **page** (`LIMIT 100`), pas la file. Tout code qui
  raisonne sur « les actions en attente » doit passer par une requête dédiée (`pendingReadIds`,
  `pendingMarkAllCount`, `pendingSavedIds`, `pendingUnsavedIds`) ; `pushQueue` enchaîne les pages
  **jusqu'à ce que la file soit vide** (pas de plafond de pages : chaque tour retire au moins une
  ligne, sinon il s'arrête). Ne jamais vider la file sur un état initial de `provider`
  (`drop(1)`), et une ligne sans flux vaut « tout le compte », pas « à jeter » — `pushPage` la
  pousse à **tous** les comptes cloud (branche « markAllRead », cibles données par
  `markAllReadTargets`, fonction pure testée), le premier ne servant qu'à porter le groupe
  (`accountFor`) : la rattacher au seul premier laissait les articles des autres non lus côté
  serveur, rouverts par la réconciliation suivante.
- `pushQueue` passe par `pushMutex` — pas `syncMutex`, non réentrant, que `syncPipeline` détient
  déjà quand il appelle la file. Sans ce verrou, deux appels qui se chevauchent (lecture d'un
  article et pipeline, par exemple) relisaient la même page avant qu'elle ne se vide et poussaient
  deux fois les mêmes lignes.
- Marquage de masse Feedly : `POST /v3/markers` accepte `type":"feeds"` (ou `"categories"`, avec ou
  sans `asOf`) puis les **ignore** — HTTP 200, corps vide, `markers/counts` inchangé (vérifié en
  direct). Seul `type":"entries"` marque vraiment : `FeedlyProvider.markAllRead` /
  `markAllReadGlobal` énumèrent donc les non-lus du flux (`streams/ids`, paquets de 100
  identifiants). Bonus : ça rattrape les articles que le serveur croit encore non lus alors que
  l'app les a marqués lus, que la réconciliation suivante rouvrirait sinon. Ne pas « optimiser » ce
  chemin en un appel de masse.
- **Feedly marque lu tout seul au-delà de 31 jours** : son état lu/non-lu ne dit plus ce que
  l'utilisateur a lu passé ce délai. `autoReadAgeMs("feedly")` = `FEEDLY_AUTO_READ_AGE_MS`, et
  `autoReadCutoff` borne **deux** chemins : `storeArticles(..., keepReadBeforeMs = …)` préserve
  l'état local des articles plus vieux déjà connus (`mergeIncomingArticle` → `beyondAutoRead`), et
  `reconcileReadState` ne ferme que les non-lus de la fenêtre (`closableUnread`). Sans ces bornes,
  chaque synchronisation refermait les vieux non-lus balayés par le service. Un article **inconnu**
  garde l'état annoncé par le serveur : il n'y a rien de local à préserver.
  La borne se coupe depuis Réglages (« Feedly : garder non lu au-delà de 31 jours », activée par
  défaut) : `PressRepository.cutoffFor(providerId)` rend alors `null`, et l'état du service fait
  foi — les six appels à `autoReadCutoff` passent par lui, un nouveau doit le faire aussi.
- Bandeau de synchro : l'avancement par flux d'un compte (découverte, puis rattrapage) vient de
  `provisionHistory(onFeed)`, relayé par `syncAccount(..., onFeed)` depuis `syncDown`. Le total est
  celui de la phase en cours — les flux en retard ne sont connus qu'après la découverte : le
  compteur est croissant et finit sur le vrai total, jamais sur le nombre d'abonnements.
- **Pas de planificateur de synchro** (le Worker a été supprimé : elle ne tourne qu'app ouverte) :
  `MainActivity` appelle `vm.syncOnLaunch()` à **chaque `ON_RESUME`**, pas seulement à la création
  de l'activité. Un déclenchement au seul `onCreate` laissait une application rouverte après des
  heures d'arrière-plan (processus vivant) sans aucune synchro, « Dernière synchro » figé à la
  veille malgré un réglage à dix minutes. C'est `syncOnLaunch` qui arbitre : option, sources et
  fenêtre de fraîcheur (`isSyncFresh`, réglage « Fréquence de synchronisation », 0 = manuelle =
  jamais frais), et il s'abstient si un travail est déjà en cours (le relancer l'annulerait).
  La **minuterie de premier plan** (`PressVM.startSyncTicker`/`stopSyncTicker`, câblés sur
  `ON_RESUME`/`ON_PAUSE`) prend le relais tant que l'écran reste visible : elle dort jusqu'à
  l'échéance du même réglage, repasse par `syncOnLaunch`, puis attend la fin de la passe
  (`syncJob?.join()` — la date n'est reculée qu'à la fin, sinon elle repartait aussitôt). Fréquence
  « manuelle » (0) : la boucle veille toujours (une fois par minute), sans quoi repasser à une
  fréquence depuis Réglages ne redémarrerait jamais la minuterie — aucun `ON_RESUME` sur cet écran.
  `attemptedAt` (mémoire) évite de retenter chaque minute une synchro en échec, `LAST_SYNC_AT`
  n'avançant qu'en cas de succès.

## Pièges connus — données, cache et stockage

- Pas de migration Room à écrire pour une simple requête DAO ou colonne déjà existante ;
  `exportSchema = false`, version DB actuelle : 8.
- **Un ajout d'`Index` sur une entité exige une migration** (Room compare le schéma et son
  empreinte à l'ouverture : sans `MIGRATION_6_7`, l'app plantait sur une base v6 existante) :
  `CREATE INDEX IF NOT EXISTS` sur les trois index des stats (`articles.readAt`,
  `reading_events.articleId`, `reading_events.openedAt`). « Passés en lu sans ouvrir » cherche un
  évènement par article lu : sans l'index sur `articleId`, la sous-requête balayait toute la table
  d'évènements une fois par article.
- **Un HTML d'article est plafonné à l'écriture** (`MAX_ARTICLE_HTML`, 300 000 caractères) :
  corps du flux dans `storeArticles`, extraction dans `ensureFull` (`capArticleHtml`). Android lit
  SQLite par fenêtre de curseur (~2 Mo) : une **seule** ligne plus grosse rend la requête
  illisible — constaté en release sur la liste des articles, « Couldn't read row 54, col 0 from
  CursorWindow », l'application se fermait. `MIGRATION_7_8` coupe une fois les corps déjà en base
  (et remet `fullFetched = 0`, l'article sera ré-extrait à l'ouverture).
- **L'écran article ne lit plus d'articles pour son balayage** : `PressVM.scanIds`
  (`dao.streamIds` → `SELECT id …`) donne les voisins. `SELECT *` chargeait 2000 articles complets,
  HTML compris, à chaque rafraîchissement, pour n'en garder que les identifiants. **Chaque écran à
  liste propre fournit la sienne** (`PressVM.openArticleWith` → `takeArticleNeighbours`, consommée
  une fois) : Favoris, Recherche (ses résultats) et Doublons (les groupes affichés, à plat) — sans
  elle, un article absent du périmètre du flux n'avait aucun voisin et le geste revenait en arrière
  au lieu de passer au suivant. Côté écran article, la liste est
  `remember` **sans clé** : le balayage change d'article sans changer d'écran, une liste
  re-dérivée par article repartirait du périmètre du flux au premier swipe.
- Readability : `content` porte **soit** la version du flux, **soit** l'extraction — `fullFetched`
  dit laquelle. Couper le mode (icône, pastille, double-tap) doit **réécrire** `content` avec la
  version du flux et remettre `fullFetched` à 0 : sans cette écriture la copie Readability restait
  en base et l'article rouvert reprenait la version extraite. Avant d'écraser `content` par une
  extraction, `saveExtractedContent` recopie le HTML du flux dans `summary` quand il est plus riche
  que l'extrait RSS (flux « complets » : Next) — c'est ce que « Revenir à l'extrait RSS » retrouve,
  sinon l'article revenait tronqué au petit extrait. La synchro, elle, réécrit
  `content`/`summary` avec les valeurs du serveur (`upsertArticles` remplace la ligne) :
  l'extraction ne survit pas à une synchro, et c'est la version du flux qui revient — comportement
  voulu.
- **Un échec d'extraction ne remplace plus le texte.** `ensureFull` rend un `FullFetch(html,
  outcome)` (`FullOutcome.OK / BLOCKED / FAILED`) : l'écran n'affiche `html` que sur `OK`, sinon
  il revient à `feedHtml(a)` — la version du flux, jamais le petit `summary` seul — et montre une
  carte qui distingue « site protégé » (anti-robot, abonnement, page JavaScript) de « site
  injoignable ou page illisible ». En ouverture automatique (épingle Readability), l'échec
  relâche l'épingle (`readabilityOn = false`). Côté base, `BLOCKED` ne purge une extraction
  vérolée **que si** `a.fullFetched` : purger un contenu de flux (Next) lui faisait perdre son
  corps au profit du petit extrait.
- `Readability.CONTENT_SELECTORS` porte `#next-single-post` : sur Next le corps est là, alors
  que le conteneur `article` embarque les commentaires (37 810 car. extraits contre 4 811 de vrai
  corps).
- `articleBlocks` ne retient que `p, li, h1..h6, pre, blockquote, figcaption, img, video, iframe,
  table` : certains flux livrent le corps en texte nu (`…texte…<br><img …>`, cas des Numériques).
  Les nœuds texte de premier niveau sont enveloppés dans un `<p>` avant sélection, sinon
  l'article s'affichait sans son texte.
- Flux RSS locaux (`provider = "local"`, identifiant = l'URL) : leur état lu est **purement local**
  (aucun serveur ne le renvoie). `syncDown` appelle donc `storeArticles(…, keepRead = true)` pour
  eux : `upsertArticles` remplace la ligne entière, et sans ce drapeau chaque synchronisation
  repassait tous les articles du flux en non lus. Les comptes cloud gardent l'inverse (l'état
  distant fait foi) — voir `mergeIncomingArticle`, où `unread`/`readAt` ne sont repris que si
  `keepRead`. Toute nouvelle écriture d'articles locaux doit passer par ce chemin.
- `deleteLocalFeed` = articles + miniatures orphelines (`gcThumbs`) + images pleine taille du cache
  Coil (`articleMediaOfFeed` → `htmlImageUrls` sur `content`/`summary` → `purgeImages`) +
  `vacuum()`. Les **traces de lecture restent** (voir la note « statistiques » ci-dessous).
  `gcThumbs` ne couvre que `filesDir/thumbs` ; une image encore réclamée par un autre flux reste en
  cache, et le nettoyage du cache Coil se voit dans le log (`images purgées : n/m`).
- Statistiques = registre conservé : les traces `reading_events`/`extract_events` **ne partent
  plus** avec un flux ni avec un compte (`deleteAccount`, désabonnement en synchro,
  `deleteLocalFeed`) — les effacer remettait les stats à zéro dès qu'on supprimait un compte. Leurs
  lectures s'affichent alors sous le libellé `DELETED_FEED_LABEL` (« Flux supprimé »), faute de
  titre en base. La remise à zéro est **manuelle** (bouton + confirmation sur l'écran Stats →
  `resetStats`) et pose `Prefs.STATS_SINCE`, borne basse appliquée par `statsLowerBound` : elle est
  indispensable au compteur « passés en lu sans ouvrir », déduit de `articles.readAt` (un état
  d'article que la remise à zéro ne touche pas).
- Suppression de masse (compte, flux, remise à zéro des stats) ⇒ `vacuum()` : `VACUUM` puis
  `PRAGMA wal_checkpoint(TRUNCATE)`. Sans le second, le `VACUUM` réécrit la base dans le WAL, le
  fichier principal garde sa taille, le WAL gonfle et « Cache utilisé » **monte** après une
  suppression (mesuré : 1,8 Mo → 120 Ko avec le point de contrôle, contre 1,8 Mo → 1,9 Mo sans).
  « articles » dans le détail du cache = les fichiers `pressia.db*` (db + WAL + shm), c'est pourquoi
  la taille ne redescend pas toute seule quand les articles partent.
- Source d'un article : `articles.feedTitle` (migration 5→6) fige le titre du flux **au moment de
  l'enregistrement**. Les listes lisaient la source dans `subscriptions` ; la ligne disparaissant
  au désabonnement, les favoris d'un flux supprimé perdaient leur provenance. `storeArticles` le
  remplit depuis l'abonnement, `Providers` depuis `origin.title` de l'entrée (couvre le tableau
  « Read later » d'un flux non abonné), `mergeIncomingArticle` le conserve si l'entrée n'en porte
  pas, `ArticleRow` et l'écran Doublons retombent dessus (et `FeedAvatar` sur l'initiale).
  `setFeedTitle` (flux local qui annonce son nom) met aussi à jour les articles du flux.
- **Un dossier vide n'affiche rien, et n'a pas de « tout le compte ».** Les requêtes de liste
  prennent `catEmpty = 1` pour « aucun dossier demandé » : `PressRepository.catScope` ne le pose
  que si `catId == null`, et rend une liste impossible (`listOf("\u0000")`, un identifiant qu'aucune
  URL ni aucun compte ne peut porter) pour un dossier sans flux.
  Avant, les deux cas se confondaient : un dossier vide affichait tous les articles de
  l'application, et « tout marquer lu » y marquait tout le compte (`PressVM.markAllRead` sort
  désormais sans rien faire).
- Articles « collants » de la session (`PressVM.keepIds`) : bornés à `KEEP_MAX` (400, les plus
  récents gardés). La requête les lie un par un (`id IN (:keepIds)`), une session en accumulait
  des milliers.
- **Sauvegarde Google : les jetons partent, le contenu des articles non.** `allowBackup="true"`
  avec `dataExtractionRules` (`res/xml/backup_rules.xml`, Android 12+ et transfert
  téléphone-à-téléphone) et `fullBackupContent` (`res/xml/backup_content.xml`, Android 11-) :
  `files/datastore` (comptes, jetons, réglages), `files/sauvegarde-auto.json` et le profil WebView
  sont sauvegardés ; `databases/pressia.db*` (la base porte le HTML des articles) et
  `files/thumbs/` sont exclus. Ce n'est pas du zèle : le quota est de **25 Mo par application**, et
  un dépassement fait sauter la sauvegarde **entière**, jetons compris. Les miniatures pèsent
  100 Mo et plus sur une base nourrie, la base plusieurs Mo ; le fichier de métadonnées, quelques
  kilo-octets. Les règles ne savent pas exclure une colonne : exclure le HTML, c'est exclure la base.
- **La copie automatique (`data/AutoBackup.kt`) est ce qui remplace la base dans la sauvegarde.**
  `writeAutoBackup()` exporte `Sections.ACCOUNTS` + `Sections.READ` — dossiers et flux **locaux
  seulement** (`exportJson(..., localFeedsOnly = true)` ; un flux de compte y perdrait son dossier,
  les dossiers des comptes n'étant pas exportés), étoiles PressIA, lus des flux locaux, jamais le
  contenu — vers `files/sauvegarde-auto.json`, en
  écriture atomique (temporaire + renommage). Elle est **réécrite au passage en arrière-plan et à la
  fin de chaque synchro** : la sauvegarde Google tourne elle aussi écran éteint, elle trouve donc
  toujours une copie à jour. `restoreAutoBackupIfEmpty()` la réimporte **au début de
  `syncOnLaunch`, avant le test de fraîcheur**, et seulement si **la base est vide**
  (`autoBackupWorthRestoring` : aucun abonnement et aucun favori). Le garde précédent — « aucun
  flux local » — laissait passer une bibliothèque d'un seul compte (Feedly) sans favori :
  réimportée à chaque lancement, elle détachait tous les flux de leurs dossiers (le fichier ne
  porte que les dossiers locaux) et remettait `LAST_SYNC_AT` à zéro, donc « Jamais synchronisé »
  jusqu'à la synchro suivante. Trois autres pièges, tous
  constatés en simulation (base supprimée, fichier conservé) :
  - `LAST_SYNC_AT` **est restauré par Google** (il vit dans DataStore) : sans le remettre à zéro,
    la première synchro est jugée « récente » et sautée — écran vide, et les lus/favoris attendent
    une passe qui ne vient pas. `RECONCILE_TOTALS` et `READLATER_HASHES` sont remis à zéro pour la
    même raison (calculés sur la base qui n'est pas revenue, ils feraient sauter la réconciliation
    et l'import du « Read later »).
  - **Ne pas réécrire la copie juste après l'import** : les articles ne sont pas revenus, écrire
    l'état de la base effacerait les lus/favoris encore en file. Elle est réécrite à la fin de la
    synchro, quand `RESTORED_STARRED` / `RESTORED_READ` ont été rejoués (limite assumée : un
    article sorti du flux entre-temps perd son état).
  - `markStarred` et `existingIds` sont **découpés par paquets de 500** dans la restauration : un
    gros jeu de favoris dépasse la limite de variables SQLite des anciens Android (~999).
- **La sauvegarde de l'application (écran Sauvegarde/Restauration, `Backup.kt`) reste le chemin
  complet** : elle exporte par sections (réglages, stats, comptes, favoris et lus) vers un fichier
  choisi par l'utilisateur, jetons exclus tant qu'il ne coche pas la case. La copie automatique
  ci-dessus n'en est qu'un sous-ensemble silencieux, pour la sauvegarde Google.

## Pièges connus — interface, navigation et gestes

- **`PressVM` : une propriété assignée depuis `init` doit être déclarée avant lui.** Ce qui est
  appelé dans le bloc s'exécute **pendant** la construction : `viewModelScope` est
  `Dispatchers.Main.immediate`, et un corps qui ne suspend pas (DataStore répond sans suspendre
  quand ses préférences sont déjà chargées dans le processus, `MutableStateFlow` avec valeur)
  s'exécute dans la foulée. `unreadOnly`, déclaré bien plus bas, était ainsi affecté alors
  que son délégué `mutableStateOf` était encore nul : plantage au démarrage
  (`NullPointerException` dans `setUnreadOnly`). Deux garde-fous depuis : la propriété est
  déclarée avant `init`, et l'appel du démarrage passe par `launch(Dispatchers.Main)` — **sans**
  `.immediate`, donc planifié après le constructeur. Un plantage qui revient sur ce chemin vient
  d'un APK antérieur au correctif, pas du code : la trace mentionne alors
  `refreshUnreadOnly` → `setUnreadOnly`, enchaînement qui n'existe plus.

- Modifier une vue article = toucher `ArticleRow` dans `Components.kt`. Le `MetaLine` (source +
  étoile + heure) est partagé par les 4 vues ; lui donner un paramètre `modifier` plutôt que d'en
  dupliquer une variante.
- La position du rond lu/non-lu (`dotAtStart`) pilote aussi celle de l'étoile de swipe : les deux
  doivent rester du même côté, sinon l'information saute d'un bout à l'autre de la ligne au geste.
  Il ne vaut que pour la vue **magazine** : en liste, carte et compacte le bouton de raccourci
  reste au bord droit, même sur un article sans image (la place de l'image montre alors le
  favicon du flux — le déplacer à gauche retirait le bouton de droite).
- Tiroir (`MainActivity`) : `gesturesEnabled = drawer.isOpen || when { current == "article" ->
  false; hierarchical -> current != "home"; else -> !(current == "stream" && !atHome) }`.
  Le balayage de bord est coupé là où le geste vers la droite a déjà un sens — article précédent
  sur l'écran article, montée d'un niveau sur l'Accueil et sur une liste de dossier ou de flux
  (`StreamScaffold` / `SubscriptionsScreen`, `onSwipeBack`) — mais **rétabli tiroir ouvert** :
  c'est ce qui permet de le refermer, le `onClose` du scrim Material étant conditionné par
  `gesturesEnabled` (sinon le clic hors du panneau ne fait rien).
- Navigation hiérarchique quand « Tous les articles » est la page d'accueil (`homeRoute ==
  "stream"`, `hierarchical` dans `MainActivity`) : racine (« Tous les articles », `stream` sans
  périmètre) → Accueil (`home`) → dossier/flux (`stream` avec périmètre). Le bouton retour et le
  balayage vers la droite remontent **d'un niveau** (`goHome()` : dossier/flux → Accueil, Accueil →
  racine) ; un article revient sur la liste du flux, comme avant. À la racine, le balayage ouvre le
  tiroir (comme sur les autres écrans d'accueil) : c'est `StreamScaffold.onSwipeBack` qui décide,
  selon `hierarchical && atRoot`. En mode « Accueil » classique, `goHome()` ramène tout à la page
  Accueil, qui est alors le dernier niveau.
- Règle générale du retour : **on remonte vers l'accueil configuré, on ne ferme qu'une fois
  dessus** (`atHome` = racine si `hierarchical`, sinon la page `home`). C'est ce qui rend le
  réglage « Page de démarrage » effectif immédiatement : changer l'accueil depuis Réglages ne
  déplace personne, mais le retour suivant depuis Réglages ramène à la dernière page de contenu
  (`backToLastContent`), puis la page courante n'étant plus l'accueil configuré, le retour suivant
  mène à la **nouvelle** page d'accueil, et c'est seulement là qu'un autre retour ferme
  l'application.
- **Retour depuis un écran utilitaire : la cible se lit dans la pile**, pas dans `lastContent`
  seul. `contentReturnTarget` (pur, testé) prend la première destination de **contenu** située
  sous l'écran courant dans `nav.currentBackStack.value` ; `lastContent` ne sert que de secours
  quand la pile n'en contient aucune. Lire `lastContent` seul rouvrait l'article : ouvert depuis
  Favoris puis quitté par le retour, il restait la « dernière page de contenu » et le retour
  suivant tentait d'y dépiler, échouait, puis le **recréait** (`nav.navigate`). Ouvrir Favoris
  depuis un article y ramène toujours : l'article est alors réellement sous l'utilitaire.
- **Chaque entrée de navigation « article » porte son propre article** (`ARTICLE_ID` rangé dans
  `entry.savedStateHandle`, relu par `getStateFlow`). Une valeur globale (`selectedArticleId`,
  supprimée) faisait afficher, en revenant sur un article antérieur après un détour par Favoris,
  celui qu'on venait d'ouvrir depuis Favoris : la destination restaurée était la bonne, mais
  l'identifiant relu ne l'était plus. Le balayage horizontal ne change pas d'entrée : il met à
  jour cet état via `ArticleScreen(onShowArticle = …)`, si bien que le retour rend l'article
  laissé sur place. L'identifiant n'entre **pas** dans la route (URL de flux local, identifiants
  d'entrée) : `nav.navigate("article")` puis
  `nav.currentBackStackEntry?.savedStateHandle?.set(ARTICLE_ID, id)`.
- **Bouton « remonter en haut » : pas d'`animateScrollToItem(0)` sur une longue liste.** Il avance
  par étapes — il réestime la hauteur des lignes et relance l'animation à chaque fois — si bien
  que la course durait des dizaines de secondes et finissait article par article.
  `LazyListState.animateToTop` / `LazyGridState.animateToTop` (`Components.kt`) font un défilement
  continu (`scrollBy(-hauteur d'écran)` + `withFrameNanos`), borné à `TOP_REWIND_MS` (600 ms),
  puis `scrollToItem(0)` pour se poser exactement en haut. Mesures : LazyList plafonne `scrollBy`
  à ~1,2 écran par image et l'émulateur ne rend que ~7 images/s pendant le défilement, d'où la
  borne de temps. Les trois listes s'en servent (articles, magazine, abonnements) ; le bouton
  « descendre », lui, garde `animateScrollToItem` (il vise la fin des non-lus).
- Balayage de retour : `rememberBackSwipeState()` + `Modifier.backSwipe(state, onSwipe)` et le
  repère `SwipeBackHint` (`StreamScreen.kt`) sont partagés par `StreamScaffold` et
  `SubscriptionsScreen`. Le détecteur est posé sur la boîte qui **enveloppe** la liste : les lignes
  d'articles, qui ont leur propre geste horizontal, le consomment et gardent leur swipe — pour
  tester le retour, balayer sur un **interligne de date** (« Hier », à ~y=347) ou sur l'espace
  libre, jamais sur une ligne d'article. Seuil 120 px, sinon un frôlement change de page.
- Réglage « Page de démarrage » (`Prefs.HOME_SCREEN`) : `PressVM.homeScreen` démarre à **null**
  (DataStore répond de façon asynchrone) et le contenu de `MainActivity` n'est composé qu'une fois
  la valeur lue (`if (home == null) { Box; return@PressIATheme }`). Sans cette attente, la
  navigation démarrait sur le défaut « Accueil » et le `NavController` **gardait** cette entrée
  quand le réglage arrivait. La route de départ du `NavHost` est ensuite figée (`startRoute`, lue
  une fois) : recréer le graphe / `key(home)` éjectait l'utilisateur de l'écran Réglages au moment
  du choix. La valeur courante, elle, est suivie en direct (`homeRoute`) et pilote `hierarchical`,
  `atHome` et `goHome()`.
- Readability « persistant » coupé : le mode complet épinglé retombe dès qu'on **quitte la vue
  article** (`LaunchedEffect(current)` dans `MainActivity` → `vm.onLeftArticle()`), pas seulement
  au retour à l'accueil ; le balayage d'un article à l'autre ne change pas de destination et le
  conserve. Un article déjà extrait, lui, reste en complet à la réouverture : c'est `fullFetched`
  en base, pas l'épingle de session.
- Toute suppression est **confirmée** : corbeille d'un flux local (`LoginScreen`, dialogue
  « Désabonner ce flux ? ») comme « Déconnecter » un compte. Les menus de flux des abonnements ne
  suppriment pas (déplacer / monter / descendre) — une nouvelle action destructrice doit passer par
  un dialogue.
- Ajout d'un flux local : sa **première synchronisation est annoncée** (`lockSync()` + `syncMsg`
  dans `PressVM.addLocalFeed`, appels appariés en `finally`), et `localFeedAdding` couvre
  l'enregistrement **et** cette sync (bouton désactivé, `done` appelé dans tous les cas) — sans
  quoi l'ajout restait muet pendant le premier téléchargement et un second appui relançait tout.
  `SubscriptionsScreen` (ajout dans un dossier) ferme sa boîte de dialogue et signale par un toast.
  Une URL **déjà abonnée** ne fait rien (`LocalFeedAdd.AlreadyExists` : ni déplacement de dossier,
  ni resynchronisation) et le dit — toast dans Comptes, message sous le champ. La comparaison
  passe par `feedUrlKey` (`LocalRssFetcher`) : schéma, `www.` de tête, barres finales et casse
  ne distinguent pas deux flux (le port et les paramètres restent comparés, en minuscules).
  Le rangement passe par `moveFeedsToFolder`, jamais par `dao.moveFeeds` direct : un dossier
  **fusionné** s'appelle `merged:…` et n'existe pas dans `categories`, l'écrire tel quel violait
  la clé étrangère et fermait l'application. `moveFeedsToFolder` ne déplace plus vers un
  identifiant inconnu (dossier supprimé ailleurs) : il ne fait rien.
- **Synchro des flux locaux** : `LocalProvider.onFeed` est appelé à chaque flux reçu (8 en
  parallèle, compteur des flux terminés) et `syncDown` en fait « Réception des articles… (flux
  locaux 2/3 : MiniMachines.net) ». Les miniatures de `syncDown(withThumbs = true)` (ajout d'un
  flux) publient leur compteur dans `syncStep` comme le pipeline — sans cela la première synchro
  restait muette pendant le téléchargement des images.
- Listes : les boutons haut/bas flottent en bas au centre, les listes qui en ont laissent 72 dp de
  `contentPadding` sous le dernier élément (articles, Accueil) pour qu'il reste lisible.
- Périmètre orphelin (`MainActivity`) : quand le flux ou le dossier courant vient d'être supprimé
  depuis un autre écran (compte déconnecté, flux désabonné), un `LaunchedEffect` remet le périmètre
  sur « Tous les articles » et `lastContent` sur l'accueil — sinon le retour arrière remontait sur
  une liste vide, y compris après un détour par une autre page secondaire. La validation d'un
  dossier doit interroger `vm.folders` **et** `vm.categories` : un dossier fusionné porte un
  identifiant `merged:…` absent des catégories brutes (faux positif sinon).
- Recherche : **base locale uniquement** (`PressRepository.search`). La recherche du compte est
  partielle (Feedly ne renvoie que quelques entrées) : l'interroger masquait les articles déjà en
  base et le nombre de résultats affiché n'était pas celui de la bibliothèque. La requête du DAO
  borne l'affichage à 200 entrées, et l'écran le dit (« affichage limité ») plutôt que de laisser
  croire que la liste s'arrête là. Les résultats suivent la **vue des articles** des Réglages
  (`vm.viewMode`) : c'est une liste d'articles comme les autres.
- Écran Recherche : son état (requête, portée, option « texte », résultats) vit dans `PressVM`, pas
  dans le composable — ouvrir un article ou passer par une autre page puis revenir doit retrouver
  les résultats, et ils ne disparaissent qu'à la fermeture de l'application. Seuls un changement de
  portée ou de l'option les effacent. La recherche porte sur le **titre** par défaut (`withBody = 0`
  dans `dao.search`, l'auteur comptant comme un titre), l'interrupteur « Inclure le texte » ajoute
  résumé et contenu. Nouvelle recherche ⇒ `searchEpoch` change et la `key(...)` autour d'`ArticleList`
  reconstruit la liste : on repart en haut (une relecture après marquage groupé, elle, ne bouge pas
  la position). Page ouverte sans requête ⇒ `FocusRequester` + clavier. « Marquer tous lus »
  s'annule par `offerUndo` / `undoMarkRead`, qui relit aussi la recherche (les lignes portent leur
  propre état lu/non-lu). L'action est une **icône** de la barre des résultats, comme celle des
  listes : `DoneAll` devient `Undo` tant que l'annulation porte sur ces résultats
  (`undoOffer.ids` tous affichés) — un marquage fait ailleurs ne se confond pas avec celui-ci.
- Choix de portée (Recherche, Favoris) : `scopeEntries` / `ScopeChooser` (`ui/ScopeChooser.kt`)
  donnent **le même ordre que la page Accueil** — dossiers dans l'ordre de `vm.folders`, chacun
  suivi de ses flux triés par `sortOrder` (un dossier fusionné rassemble les flux de tous ses
  identifiants), puis les flux sans dossier — et les **mêmes icônes** : dossier, favicon du flux
  (`FeedAvatar`, décalé de 26 dp sous son dossier). Ne pas réénumérer `vm.categories` ou `vm.subs`
  à la main dans un nouvel écran : l'ordre et les repères divergeraient. Le défilement et la
  hauteur maximale restent à l'appelant (`heightIn(max = …)` **avant** `verticalScroll`, sinon la
  limite porte sur le contenu et le panneau déborde sans défiler).
- Écran Doublons : la requête couvre **tous** les non lus (`dao.unreadDuplicates`, **sans**
  `LIMIT`) et le regroupement se fait en Kotlin (`groupDuplicates`, testé). Un `LIMIT 200` trié par
  date rendait invisibles les doublons anciens — l'écran annonçait alors « aucun doublon » alors
  qu'il y en avait. L'écran est une `LazyColumn` : sans plafond, tout composer d'un coup peinerait
  sur une grosse base.

## Commit automatique

`.opencode/plugins/autocommit.js` (API des plugins OpenCode 2.x) sait commit + push tout seul
(`git add -A`, message `Auto-commit <date>`) à chaque `session.idle`, mais il est **désactivé** :
`ENABLED = false` en tête du fichier, et `setup()` rend la main sans rien écouter. Repasser la
constante à `true` (puis redémarrer OpenCode) pour le réactiver. Un commit manuel avec un vrai
message reste préférable quand l'utilisateur le demande.
