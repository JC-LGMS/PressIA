package com.pressia.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Un changement livré : sa date et son heure, son titre et, quand le commit en portait un, son
 * détail. L'heure ([time], `HH:mm`) est celle du commit : elle s'affiche sous la date et
 * départage deux changements du même jour.
 */
data class ChangelogItem(
    val date: String,
    val title: String,
    val detail: String? = null,
    val time: String? = null
)

/** Une version publiée et ses changements, du plus récent au plus ancien. */
data class ChangelogEntry(val version: String, val items: List<ChangelogItem>)

/**
 * Journal des modifications, du plus récent au plus ancien.
 *
 * **À tenir à jour** (voir AGENTS.md) : à chaque commit qui touche l'application, ajouter un
 * [ChangelogItem] en tête de l'entrée de la version courante, avec le titre et le corps du
 * message de commit. Le numéro de version, lui, avance tout seul au build suivant
 * (`version.properties`) ; c'est donc la version affichée par [ChangelogScreen] qu'il faut
 * reprendre dans l'entrée. L'historique d'avant le journal (208 commits) est dans
 * [CHANGELOG_HISTORY], généré une fois depuis `git log` et à ne plus modifier.
 */
val CHANGELOG: List<ChangelogEntry> = listOf(
    ChangelogEntry(
        "1.0.29",
        listOf(
            ChangelogItem(
                "2026-11-05",
                "Plafond de taille pour les flux et les pages d'articles",
                "Les corps de flux et les pages d'articles partaient entiers en mémoire, sans " +
                    "plafond : huit flux interrogés en parallèle, un serveur qui répond une " +
                    "archive ou un corps sans fin, et la synchro mourait en OutOfMemory. Le " +
                    "même plafond qu'une miniature s'applique désormais - 5 Mo pour un flux, " +
                    "8 Mo pour une page d'article - et un dépassement est un échec de " +
                    "récupération, journalisé pour le flux local concerné.",
                time = "09:53"
            )
        )
    ),
    ChangelogEntry(
        "1.0.28",
        listOf(
            ChangelogItem(
                "2026-11-05",
                "Minuterie de synchronisation au premier plan",
                "Le retour au premier plan resynchronisait, mais une application laissée ouverte " +
                    "au même écran ne se resynchronisait jamais non plus : la minuterie manquait. " +
                    "Tant que l'écran est visible, une boucle dort jusqu'à l'échéance du réglage " +
                    "« Fréquence de synchronisation » puis repasse par la même porte (option, " +
                    "sources et fraîcheur revérifiées), et attend la fin de la passe. Elle s'arrête " +
                    "quand l'application passe en arrière-plan ; fréquence « manuelle » : elle ne " +
                    "synchronise pas mais reste à l'écoute d'un changement de réglage. Une " +
                    "tentative en échec n'est pas retentée toutes les minutes.",
                time = "08:42"
            )
        )
    ),
    ChangelogEntry(
        "1.0.27",
        listOf(
            ChangelogItem(
                "2026-11-05",
                "Synchronisation au retour dans l'application",
                "La synchro automatique ne partait qu'à la création de l'activité : une " +
                    "application laissée en arrière-plan puis rouverte (processus toujours vivant) " +
                    "ne resynchronisait jamais, et « Dernière synchro » pouvait dater de la veille " +
                    "malgré un réglage à dix minutes. Chaque retour au premier plan repasse " +
                    "désormais par la même porte — option et fenêtre de fraîcheur revérifiées — et " +
                    "n'annule pas une synchro déjà en cours.",
                time = "08:11"
            )
        )
    ),
    ChangelogEntry(
        "1.0.26",
        listOf(
            ChangelogItem(
                "2026-11-05",
                "Vidéo : un seul lecteur, celui du site ; le retour fonctionne",
                "Le réglage « Vidéos YouTube » disparaît, et avec lui le lecteur maison : la vidéo " +
                    "se lit désormais sur la page d'intégration de YouTube, avec ses propres " +
                    "commandes (lecture, barre d'avancée, qualité, sous-titres) — et elle ne se met " +
                    "plus en pause toute seule quelques secondes après le début, ce que faisait la " +
                    "page de lecture (son lecteur se croit invisible). " +
                    "Autre correctif : quand la vidéo jouait, l'appui sur Retour était consommé " +
                    "avant l'application — il ne quittait plus l'article, et il arrêtait même la " +
                    "lecture au passage. La touche est maintenant rendue à l'application, en plein " +
                    "écran comme dans l'article : le plein écran se referme d'abord, l'article se " +
                    "quitte ensuite.",
                time = "00:28"
            )
        )
    ),
    ChangelogEntry(
        "1.0.25",
        listOf(
            ChangelogItem(
                "2026-11-04",
                "Statistiques indexées, rafraîchissement allégé",
                "« Passés en lu sans ouvrir » cherchait, pour chaque article lu, un évènement de " +
                    "lecture : la sous-requête balayait toute la table d'évènements, une fois par " +
                    "article. Les colonnes articles.readAt, reading_events.articleId et " +
                    "reading_events.openedAt sont indexées (base 6 → 7). Et chaque " +
                    "rafraîchissement chargeait 2000 articles complets, HTML compris, pour n'en " +
                    "garder que les identifiants du balayage : une requête d'identifiants seuls " +
                    "les fournit.",
                time = "08:58"
            )
        )
    ),
    ChangelogEntry(
        "1.0.24",
        listOf(
            ChangelogItem(
                "2026-11-04",
                "Inoreader, doublons et comptes parallèles : requêtes et verrous",
                "« Tout marquer lu » sur Inoreader demandait le jeton CSRF article par article, " +
                    "soit deux requêtes HTTP par article : il est demandé une fois pour la " +
                    "boucle. « Marquer lus tous les doublons » vidait la file de synchronisation " +
                    "une fois par doublon ; un seul envoi groupé la vide une fois. Enfin, des " +
                    "comptes synchronisés en parallèle perdaient des préférences (sessions " +
                    "expirées, totaux de réconciliation) : ces tables JSON de DataStore se " +
                    "modifient désormais sous verrou.",
                time = "08:06"
            )
        )
    ),
    ChangelogEntry(
        "1.0.23",
        listOf(
            ChangelogItem(
                "2026-11-04",
                "Dossier vide : plus aucun article ; articles collants bornés",
                "Un dossier sans flux affichait tous les articles de l'application : la requête " +
                    "prenait « dossier vide » pour « pas de dossier ». Il n'affiche plus rien, et " +
                    "« tout marquer lu » n'y marque plus tout le compte (même confusion). D'autre " +
                    "part, les articles gardés visibles après lecture pendant la session " +
                    "s'accumulaient sans limite, chacun lié un par un dans la requête : la liste " +
                    "est bornée à 400, les plus récents gardés.",
                time = "01:43"
            )
        )
    ),
    ChangelogEntry(
        "1.0.22",
        listOf(
            ChangelogItem(
                "2026-11-04",
                "Sélection : chercher et traduire ; Feedly garde ses non-lus",
                "Le menu de sélection d'un article offrait « Copier » et « Tout sélectionner » ; " +
                    "il ajoute « Rechercher sur Internet » et « Traduire », qui ouvrent le " +
                    "navigateur sur le texte sélectionné. Compose ne livre pas la sélection à ce " +
                    "menu : les deux actions la font copier, puis relisent le presse-papiers (le " +
                    "texte est donc copié au passage). Côté réglages, une option « Feedly : " +
                    "garder non lu au-delà de 31 jours », activée par défaut, empêche les " +
                    "synchronisations de fermer les articles que Feedly marque lus de lui-même " +
                    "passé ce délai : leur état non lu est conservé.",
                time = "01:27"
            )
        )
    ),
    ChangelogEntry(
        "1.0.21",
        listOf(
            ChangelogItem(
                "2026-11-03",
                "Retour sur un article : c'est bien celui qu'on avait laissé",
                "L'article affiché était une valeur unique de l'application, réécrite à chaque " +
                    "ouverture : après un détour par Favoris (ou la Recherche), revenir sur " +
                    "l'article ouvert auparavant rendait celui qu'on venait d'ouvrir depuis " +
                    "Favoris. Chaque entrée de navigation porte désormais son propre article, y " +
                    "compris celui atteint par balayage horizontal : le retour rend exactement " +
                    "l'article laissé à cet endroit. Le corrigé précédent s'applique toujours " +
                    "(retour depuis Favoris vers la page d'où l'on venait).",
                time = "00:04"
            )
        )
    ),
    ChangelogEntry(
        "1.0.20",
        listOf(
            ChangelogItem(
                "2026-11-02",
                "Retour depuis Favoris : l'article ne se rouvre plus",
                "Depuis Favoris, ouvrir un article puis revenir à la liste laissait l'article " +
                    "comme « dernière page de contenu » : le retour suivant, au lieu de rendre " +
                    "la page d'où l'on venait, dépilait vers une destination disparue puis la " +
                    "recréait — l'article se rouvrait. La cible du retour est maintenant lue " +
                    "dans la pile de navigation (la première page de contenu réellement située " +
                    "sous l'écran utilitaire), la mémoire de la dernière page ne servant que de " +
                    "secours. Ouvrir Favoris depuis un article y ramène toujours, comme avant.",
                time = "23:48"
            )
        )
    ),
    ChangelogEntry(
        "1.0.19",
        listOf(
            ChangelogItem(
                "2026-11-02",
                "Liste : le bouton haut remonte d'un trait",
                "Sur une très longue liste, le bouton « remonter en haut » avançait par étapes : " +
                    "chaque saut réestimait la hauteur des lignes et relançait l'animation, si " +
                    "bien que la course durait des dizaines de secondes et finissait article par " +
                    "article. La remontée est maintenant un défilement continu, à la vitesse " +
                    "maximale que la liste peut composer, borné dans le temps : quelques images " +
                    "suffisent quel que soit le nombre d'articles, puis la liste se pose " +
                    "exactement tout en haut. Les trois listes (articles, vue magazine, " +
                    "abonnements) en profitent.",
                time = "23:14"
            )
        )
    ),
    ChangelogEntry(
        "1.0.18",
        listOf(
            ChangelogItem(
                "2026-11-02",
                "Favoris : un article lu depuis la liste ne s'y invite plus",
                "La liste « collante » garde visibles les articles passés en lus pendant la " +
                    "session, pour qu'ils ne disparaissent pas sous le doigt. Cet égard ignorait " +
                    "le périmètre : l'article ouvert depuis « Tous les articles » réapparaissait " +
                    "donc en tête de Favoris alors qu'il n'y est pas étoilé. Les articles " +
                    "retenus ne sont plus dispensés que du filtre « non-lus seuls » : le flux " +
                    "affiché et le jeu de favoris s'appliquent aussi à eux.",
                time = "22:53"
            )
        )
    ),
    ChangelogEntry(
        "1.0.17",
        listOf(
            ChangelogItem(
                "2026-11-01",
                "Liste : l'appui long ne fait plus sauter la vue",
                "Le menu d'appui long (« marquer lus les plus récents / plus anciens ») écrivait " +
                    "en base AVANT de rendre les articles « collants ». Sous le filtre « non-lus " +
                    "seuls » (par défaut), la liste réactive les retirait donc le temps d'une " +
                    "émission, puis les réaffichait : les lignes disparaissaient et revenaient, " +
                    "et le défilement sautait de position au lieu de rester sur l'article " +
                    "regardé. Les identifiants sont maintenant ajoutés aux articles « collants » " +
                    "avant l'écriture, comme pour une lecture simple : ils restent en place, " +
                    "simplement estompés.",
                time = "16:31"
            )
        )
    ),
    ChangelogEntry(
        "1.0.16",
        listOf(
            ChangelogItem(
                "2026-11-01",
                "Vidéo YouTube : bouton plein écran à deux icônes",
                "Le bouton plein écran de la barre du lecteur ne se distinguait pas d'un coin " +
                    "de l'image : un carré vide, à peine plus clair en plein écran, et les " +
                    "commandes du site (CC, roue, ⤢, progression) s'affichaient par-dessus les " +
                    "nôtres dans le même coin une fois la vidéo assez grande — d'où des appuis " +
                    "qui réduisaient la vidéo au lieu de la mettre en pause, et d'autres qui la " +
                    "rétablissaient. Le bouton porte maintenant les deux icônes de " +
                    "l'application (agrandir / réduire) et devient rouge en plein écran, les " +
                    "commandes de YouTube sont masquées, et le mini-lecteur du site ne s'installe " +
                    "plus. Au passage, la lecture et l'arrêt passent par l'API du lecteur YouTube " +
                    "(comme ses boutons) : ils ne déclenchent plus de rechargement de la vidéo, " +
                    "et l'icône lecture/pause suit l'état réel du lecteur.",
                time = "02:00"
            )
        )
    ),
    ChangelogEntry(
        "1.0.15",
        listOf(
            ChangelogItem(
                "2026-10-31",
                "Vidéo YouTube : interface au choix, plein écran natif",
                "Un réglage « Vidéos YouTube » choisit la présentation du lecteur : « Contrôles " +
                    "PressIA » (défaut) garde la page de lecture, où YouTube livre un lecteur " +
                    "sans commandes et où l'image est composée de façon fiable — l'application " +
                    "pose les siennes (lecture/pause, ±10 s, barre, son, vitesses, boucle) ; " +
                    "« Interface YouTube » charge la page d'intégration avec un User-Agent de " +
                    "navigateur, et YouTube affiche alors ses propres contrôles, qui sont de " +
                    "vrais boutons. Le plein écran est désormais celui de l'application : la vue " +
                    "est déplacée sur toute la fenêtre, en paysage, sans recharger la page — donc " +
                    "sans relancer la vidéo —, et ✕ (ou Retour) la remet en place. En mode " +
                    "PressIA, un garde-fou empêche YouTube d'annuler la lecture au retour du " +
                    "plein écran : il ignorait chaque reprise, la vidéo restait bloquée en pause.",
                time = "10:42"
            )
        )
    ),
    ChangelogEntry(
        "1.0.14",
        listOf(
            ChangelogItem(
                "2026-10-31",
                "Article : liens dans le texte, texte sélectionnable",
                "Un lien du corps de l'article passait à la ligne : il formait un bloc à part au " +
                    "milieu d'une phrase. Un conteneur (p, li, titre, citation) est maintenant " +
                    "coulé d'un seul tenant, ses liens n'étant que des plages cliquables — " +
                    "couleur dominante, soulignées — dont le tap ouvre le navigateur. Seul un " +
                    "lien hors de tout paragraphe reste un bloc à part. Au passage, le texte en " +
                    "ligne (gras, italique, span) n'est plus perdu : il n'était ni texte, ni " +
                    "lien, ni média, et disparaissait du paragraphe. Le texte de l'article est " +
                    "par ailleurs sélectionnable (appui long) : poignées et « Copier / Tout " +
                    "sélectionner », sans gêner le balayage d'un article à l'autre.",
                time = "04:17"
            )
        )
    ),
    ChangelogEntry(
        "1.0.13",
        listOf(
            ChangelogItem(
                "2026-10-27",
                "Vidéo YouTube : des contrôles qui répondent",
                "Les contrôles du lecteur mobile YouTube sont peints sur un canevas : pause, " +
                    "réglages et barre d'avancée restaient sans effet, quel que soit l'endroit " +
                    "où l'on appuyait. Le lecteur reçoit désormais ses propres contrôles, en " +
                    "éléments réels de la page : un appui sur l'image met en pause ou reprend, " +
                    "un double appui à gauche/droite recule ou avance de 10 s, et une barre en " +
                    "bas donne lecture/pause, temps écoulé et durée, avancée (appui et " +
                    "glissement), son et réglages. Le menu des réglages propose la vitesse de " +
                    "lecture (0,5× à 2×) et la lecture en boucle. Le son reste actif même " +
                    "lorsque le lecteur le coupe de lui-même, sauf si on le coupe depuis la " +
                    "barre.",
                time = "20:50"
            )
        )
    ),
    ChangelogEntry(
        "1.0.12",
        listOf(
            ChangelogItem(
                "2026-10-27",
                "Vidéo YouTube : la barre d'avancée avance vraiment",
                "Sur le lecteur mobile, la barre d'avancée est peinte sur un canevas : " +
                    "aucun appui ni glissement ne produisait de recherche (au mieux l'image du " +
                    "temps visé, puis la lecture reprenait au temps précédent), et le document " +
                    "ne contient aucun élément de barre à rendre cliquable. Une barre propre au " +
                    "lecteur est donc injectée dans la page : elle reçoit les gestes directement " +
                    "(et non le navigateur, qui annulait le geste par un `pointercancel` dès " +
                    "quelques pixels de course) et déplace `video.currentTime`. Le bloc vidéo " +
                    "n'est plus peint en noir avant d'avoir sa taille — plus de trame noire sur " +
                    "tout l'écran à l'appui — et un doigt posé sur un lecteur n'est plus pris " +
                    "pour un balayage d'article.",
                time = "19:56"
            )
        )
    ),
    ChangelogEntry(
        "1.0.11",
        listOf(
            ChangelogItem(
                "2026-10-27",
                "Démarrage : le filtre « non-lus seuls » ne peut plus planter",
                "Le réglage du filtre était lu dans le constructeur du ViewModel : quand " +
                    "DataStore répond sans suspendre (préférences déjà en mémoire), " +
                    "l'affectation tombait pendant la construction, avant que l'état Compose " +
                    "n'existe — NullPointerException au démarrage. La propriété est déclarée " +
                    "avant le bloc d'initialisation, et la lecture de démarrage est désormais " +
                    "planifiée sur `Dispatchers.Main` sans `.immediate` : elle s'exécute après " +
                    "le constructeur, quel que soit l'ordre des déclarations. `loaded`, affecté " +
                    "par le premier remplissage, est remonté de la même façon.",
                time = "12:45"
            )
        )
    ),
    ChangelogEntry(
        "1.0.10",
        listOf(
            ChangelogItem(
                "2026-10-27",
                "Vidéo : plus de lecture à l'ouverture de l'article",
                "Le lecteur YouTube intégré démarrait tout seul : la page de lecture se lance " +
                    "d'elle-même une fois chargée, et la WebView était créée dès l'affichage de " +
                    "l'article — le son partait avant tout geste. Rien n'est désormais chargé " +
                    "sans appui : le bloc montre l'affiche de la vidéo, et c'est le tap qui " +
                    "ouvre le lecteur. Le délai de secours (20 s) ne court plus qu'à partir de " +
                    "ce tap, sinon il aurait déclaré l'échec pendant que l'affiche était encore " +
                    "affichée. L'affiche et le bouton sont maintenant un seul composant, " +
                    "partagé par le lecteur YouTube, la vidéo `<video>` et le repli « ouvrir " +
                    "sur YouTube ».",
                time = "08:39"
            )
        )
    ),
    ChangelogEntry(
        "1.0.9",
        listOf(
            ChangelogItem(
                "2026-10-26",
                "YouTube : l'image revient ; lecteur natif corrigé",
                "Le lecteur YouTube intégré affichait le son sans l'image : la page " +
                    "d'intégration (`youtube.com/embed/…`) alimente bien le décodeur vidéo, mais " +
                    "aucune image n'atteint l'écran — alors que la page de lecture " +
                    "(`m.youtube.com/watch`), document de premier niveau, rend la vidéo " +
                    "normalement dans la même WebView. C'est aussi ce que fait le tap sur le " +
                    "titre de l'intégration, et c'est ce qui fonctionnait. Le lecteur charge " +
                    "donc la page de lecture, habillée d'une feuille de style injectée qui " +
                    "masque l'en-tête et les contenus annexes et fixe le lecteur en haut : le " +
                    "bloc 16/9 ne montre que la vidéo. La lecture d'erreur lue dans la page, qui " +
                    "faisait basculer sur l'affiche, disparaît (elle se déclenchait à tort et " +
                    "ouvrait l'application YouTube). Enfin, le lecteur `<video>` natif " +
                    "démarrait avant sa préparation — donc jamais : il lance la lecture dans " +
                    "`onPrepared`, et n'est plus créé hors de la fenêtre.",
                time = "00:55"
            )
        )
    ),
    ChangelogEntry(
        "1.0.8",
        listOf(
            ChangelogItem(
                "2026-10-26",
                "Article : barre de défilement ; journal : barre plus fine",
                "L'écran article dessine enfin une barre de défilement au bord droit : un curseur " +
                    "dont la longueur est la part visible de l'article et la position " +
                    "l'avancement (calculés dans la phase de dessin, un défilement ne " +
                    "recompose rien). Largeur 3 dp, dans la marge de 16 dp du texte ; longueur " +
                    "minimale 36 dp pour rester visible sur un très long article ; rien n'est " +
                    "dessiné quand le texte tient à l'écran, ni pendant un balayage (l'icône du " +
                    "geste occupe ce bord). Celle du journal passe de 6 dp à 3 dp, pour le même " +
                    "repère discret.",
                time = "00:32"
            )
        )
    ),
    ChangelogEntry(
        "1.0.7",
        listOf(
            ChangelogItem(
                "2026-10-26",
                "Démarrage : plus de plantage du filtre « non-lus seuls »",
                "Le ViewModel affectait le filtre lu/non-lu avant que son état Compose ne " +
                    "soit créé : quand les préférences étaient déjà en mémoire (processus " +
                    "réveillé par la synchronisation, activité recréée), l'affectation se " +
                    "faisait pendant la construction et l'application plantait " +
                    "(NullPointerException). La propriété est désormais déclarée avant le " +
                    "bloc d'initialisation, avec la règle en commentaire.",
                time = "00:16"
            )
        )
    ),
    ChangelogEntry(
        "1.0.6",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Feedly : les vieux non-lus ne sont plus refermés par la synchro",
                "Feedly marque automatiquement en lu tous les articles non lus de plus de " +
                    "31 jours. L'état lu/non-lu du service n'est donc plus appliqué au-delà " +
                    "de ce délai : un article déjà connu garde son état local (lu ou non lu), " +
                    "et la réconciliation ne ferme plus les non-lus plus vieux que la " +
                    "fenêtre. Un article qui arrive pour la première fois garde l'état " +
                    "annoncé par le serveur.",
                time = "23:52"
            )
        )
    ),
    ChangelogEntry(
        "1.0.5",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Version : un incrément par évolution, pas par compilation",
                "Le numéro de version n'avance plus à chaque compilation : tant que " +
                    "l'évolution en cours n'est pas commitée, elle garde le numéro qu'elle a " +
                    "reçu au premier build. Corriger puis recompiler dix fois ne fait donc " +
                    "plus avancer de dix correctifs ; le commit suivant ouvre la version " +
                    "suivante.",
                time = "22:41"
            )
        )
    ),
    ChangelogEntry(
        "1.0.4",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Session expirée : raccourci « Reconnecter » dans le bandeau",
                "Le message de session expirée porte un bouton « Reconnecter » qui ouvre " +
                    "directement la page de connexion du service du compte concerné (Feedly, " +
                    "Inoreader), sans passer par Comptes ; Instapaper et un identifiant inconnu " +
                    "renvoient à Comptes. Le bandeau reste affiché 12 s au lieu de 4 s pour " +
                    "laisser le temps de toucher le raccourci.",
                time = "01:50"
            ),
            ChangelogItem(
                "2026-10-25",
                "Reconnexion d'un compte récent : synchro normale",
                "Une reconnexion (même compte, nouveau jeton) dont la dernière synchro réussie a " +
                    "moins d'un mois lance la synchronisation normale au lieu de la première " +
                    "synchronisation d'historique : l'historique est déjà en base. Un compte " +
                    "inconnu, ou resté plus d'un mois sans synchro, reprend la passe complète.",
                time = "01:50"
            ),
            ChangelogItem(
                "2026-10-25",
                "À propos : fonctionnalités et règles à jour",
                "Comptes Inoreader, reconnexion sans historique, raccourci de session expirée, " +
                    "recherche locale (titre par défaut, option texte, borne à 200 résultats, " +
                    "marquage annulable), doublons sur tous les non-lus, source des favoris " +
                    "conservée, et nouvelle entrée « Version et journal des modifications ».",
                time = "01:50"
            )
        )
    ),
    ChangelogEntry(
        "1.0.3",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Journal des modifications : barre de défilement",
                "Le journal affiche une barre de défilement au bord droit de la liste : la " +
                    "longueur du curseur indique la part visible des 200 et quelques changements. " +
                    "Elle n'apparaît que si la liste dépasse l'écran.",
                time = "01:41"
            )
        )
    ),
    ChangelogEntry(
        "1.0.1",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Journal des modifications : heure et minute sous chaque date",
                "Chaque changement du journal affiche désormais l'heure du commit (`HH:mm`) sous " +
                    "sa date, ce qui départage deux changements du même jour. L'historique a été " +
                    "régénéré avec ces horaires.",
                time = "01:34"
            )
        )
    ),
    ChangelogEntry(
        "1.0.0",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Journal des modifications",
                "Page accessible en touchant le numéro de version et la date de build (tiroir, " +
                    "À propos). Le numéro de version avance automatiquement à chaque modification " +
                    "de l'application : plus besoin d'y penser.",
                time = "01:29"
            )
        ) + CHANGELOG_HISTORY
    )
)

/**
 * Écran « Journal des modifications » : version courante (celle du build, avec sa date) puis,
 * version par version, les changements repris des messages de commit.
 */
@Composable
fun ChangelogScreen() {
    val ctx = LocalContext.current
    val version = remember { appVersionName(ctx) }
    val total = remember { CHANGELOG.sumOf { it.items.size } }
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
            Text(
                "Journal des modifications",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (version.isNullOrBlank()) "Version installée inconnue" else "Version installée : $version",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "$total changement(s) consigné(s), du plus récent au plus ancien",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Toucher le numéro de version (tiroir ou à propos) rouvre cette page.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider()
        // La liste et sa barre de défilement partagent le même espace : la barre se pose au bord
        // droit, sur la marge que le contenu laisse libre (contentPadding de fin), sans masquer
        // le texte.
        Box(Modifier.fillMaxSize()) {
            // Une ligne par changement : la liste reste paresseuse, l'historique fait plus de
            // 200 entrées et tout composer d'un coup serait inutilement coûteux.
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 32.dp)
            ) {
                CHANGELOG.forEach { entry ->
                    item {
                        Text(
                            "Version ${entry.version}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "${entry.items.size} changement(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    items(entry.items) { item -> ChangelogRow(item) }
                    item {
                        Spacer(Modifier.height(18.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            // Barre de défilement : posée au bord droit, sur la marge de fin (20 dp) du contenu.
            // 3 dp de large, comme celle de l'écran article : un repère de position, pas un
            // élément de décor.
            ChangelogScrollBar(
                listState,
                Modifier.align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .padding(top = 14.dp, bottom = 32.dp, end = 5.dp)
                    .width(3.dp)
            )
        }
    }
}

/**
 * Barre de défilement verticale du journal (un LazyColumn n'en dessine pas).
 *
 * Le dessin lit l'état de la liste dans la phase de dessin : un défilement ne redessine que la
 * barre, la page n'est pas recomposée. La barre n'apparaît que si la liste dépasse l'écran, et
 * la longueur du curseur reflète la part visible du contenu.
 */
@Composable
private fun ChangelogScrollBar(state: LazyListState, modifier: Modifier = Modifier) {
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    Canvas(modifier) {
        val info = state.layoutInfo
        val total = info.totalItemsCount
        val visible = info.visibleItemsInfo
        // Rien à faire défiler : pas de barre.
        if (total <= 0 || visible.isEmpty() || visible.size >= total) return@Canvas
        val radius = size.width / 2f
        drawRoundRect(
            color = trackColor,
            size = size,
            cornerRadius = CornerRadius(radius, radius)
        )
        // Part visible du contenu, avec un minimum lisible (une ligne très haute ne doit pas
        // réduire le curseur à un trait invisible).
        val thumbHeight = (size.height * visible.size / total)
            .coerceIn(36.dp.toPx().coerceAtMost(size.height), size.height)
        val maxTop = (size.height - thumbHeight).coerceAtLeast(0f)
        val first = visible.first()
        // Position : index du premier élément visible, plus la part déjà défilée dedans.
        val inside = if (first.size > 0) {
            (state.firstVisibleItemScrollOffset.toFloat() / first.size).coerceIn(0f, 1f)
        } else 0f
        val maxStart = (total - visible.size).coerceAtLeast(1)
        val progress = ((first.index + inside) / maxStart).coerceIn(0f, 1f)
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(0f, maxTop * progress),
            size = Size(size.width, thumbHeight),
            cornerRadius = CornerRadius(radius, radius)
        )
    }
}

/** Une ligne du journal : date et heure à gauche, titre puis détail du commit à droite. */
@Composable
private fun ChangelogRow(item: ChangelogItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        // Colonne étroite : la date sur une ligne, l'heure juste en dessous.
        Column(Modifier.width(84.dp).padding(top = 2.dp, end = 6.dp)) {
            Text(
                item.date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!item.time.isNullOrBlank()) {
                Text(
                    item.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium)
            if (!item.detail.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
