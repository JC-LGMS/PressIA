package com.pressia.data

import com.pressia.data.local.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copie de la sauvegarde de l'application, tenue à jour dans `files/`.
 *
 * C'est le seul endroit où vivent les flux RSS locaux, les favoris et les états de lecture une
 * fois la base exclue de la sauvegarde Google (elle porte le HTML des articles, qui la fait
 * dépasser le quota de 25 Mo — voir `res/xml/backup_rules.xml`). Le fichier, lui, reste petit :
 * c'est de la liste, pas du contenu.
 */
const val AUTO_BACKUP_NAME = "sauvegarde-auto.json"

/**
 * Sections de la copie automatique — exactement ce que la base exclue ne sauvegarde plus :
 * les dossiers et flux **locaux** (ceux des comptes se redécouvrent à la synchro), les favoris
 * PressIA et les articles lus des flux saisis à la main (voir [Sections.READ]). Jamais le
 * contenu des articles.
 */
private val AUTO_SECTIONS = listOf(Sections.ACCOUNTS, Sections.READ)

/** Deux passes peuvent se croiser (arrière-plan et fin de synchro) : une seule écrit à la fois. */
private val autoBackupWrite = kotlinx.coroutines.sync.Mutex()

fun PressRepository.autoBackupFile(): File = File(appCtx.filesDir, AUTO_BACKUP_NAME)

/**
 * Marqueur « une copie a déjà été écrite sur cet appareil ». Il vit dans `noBackupFilesDir`,
 * jamais sauvegardé ni transféré : une vraie restauration Google arrive donc **sans** lui (base
 * absente, copie revenue, marqueur non), tandis qu'une bibliothèque vidée exprès le laisse en
 * place — c'est ce qui distingue les deux cas.
 */
fun PressRepository.autoBackupMarkFile(): File =
    File(appCtx.noBackupFilesDir, AUTO_BACKUP_NAME + ".ecrit")

/**
 * Réécrit la copie automatique. Appelée quand l'application passe en arrière-plan et à la fin
 * d'une synchronisation : la sauvegarde Google tourne elle aussi écran éteint, une copie à jour
 * à ce moment-là est donc tout ce dont elle a besoin.
 *
 * Écriture par fichier temporaire : une copie tronquée (arrêt en pleine écriture) ne doit pas
 * remplacer la précédente, seule source des flux locaux.
 */
suspend fun PressRepository.writeAutoBackup() = withContext(Dispatchers.IO) {
    autoBackupWrite.withLock {
        runCatching {
            // Flux locaux seulement : la base exclue de la sauvegarde Google ne contient de toute
            // façon que les leurs (dossiers locaux), et y mêler ceux des comptes les détacherait de
            // leurs dossiers au premier réimport.
            val json = exportJson(AUTO_SECTIONS, localFeedsOnly = true)
            val target = autoBackupFile()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(json)
            // Remplacement atomique : une copie tronquée (arrêt en pleine écriture) ne doit pas
            // remplacer la précédente, seule source des flux locaux.
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Certains systèmes de fichiers refusent le remplacement atomique : un
                // remplacement simple vaut mieux qu'une copie jamais réécrite.
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
            // La base a bien été vue par une écriture : une base vide désormais est un vidage
            // volontaire, pas une restauration Google (voir `autoBackupMarkFile`).
            autoBackupMarkFile().createNewFile()
        }
        Unit
    }
}

/**
 * Réimporte la copie automatique quand elle est la seule chose qui reste : c'est le cas d'une
 * première ouverture après une restauration Google (la base n'est pas revenue, ce fichier-là
 * si). Rend vrai si une restauration a eu lieu.
 *
 * Deux gardes, ensemble : **aucun abonnement et aucun favori** en base, et **aucune copie déjà
 * écrite** sur cet appareil (voir [autoBackupMarkFile]). La seconde ferme le trou de la
 * première : retirer le dernier flux ou favori puis forcer l'arrêt avant l'écriture de fond
 * laissait l'ancien fichier en place, et la bibliothèque vidée revenait au lancement suivant.
 * La synchronisation des comptes peut tourner avant sans gêner : elle ne crée ni flux local ni
 * favori PressIA.
 *
 * Les articles, eux, n'arrivent qu'avec la synchronisation des flux : favoris et lus sont donc
 * mis en file (`RESTORED_STARRED`, `RESTORED_READ`) et rejoués à leur arrivée, exactement comme
 * pour la restauration manuelle.
 *
 * Le garde « base vide » refuse aussi une bibliothèque vivante composée d'un seul compte
 * (Feedly) sans favori : le fichier ne contient que les dossiers **locaux**, et chaque flux du
 * compte perdait alors son dossier jusqu'à la fin de la synchro suivante. Pur, testé.
 */
fun autoBackupWorthRestoring(
    subscriptions: Int,
    starred: Int,
    alreadyWritten: Boolean
): Boolean = subscriptions == 0 && starred == 0 && !alreadyWritten

suspend fun PressRepository.restoreAutoBackupIfEmpty(): Boolean = withContext(Dispatchers.IO) {
    val dao = db.dao()
    if (!autoBackupWorthRestoring(
            dao.subscriptions().size, dao.starredCount(), autoBackupMarkFile().exists()
        )
    ) return@withContext false
    val file = autoBackupFile()
    if (!file.exists()) return@withContext false
    val json = runCatching { file.readText() }.getOrNull() ?: return@withContext false
    if (runCatching { importJson(json, AUTO_SECTIONS) }.isFailure) return@withContext false
    // Caches et garde-fous calculés sur la base qui n'est pas revenue : ils mentent, et deux
    // d'entre eux bloqueraient la reprise. La fraîcheur de synchro ferait sauter la première
    // passe (bibliothèque vide à l'écran), et l'empreinte « Read later » ferait sauter l'import
    // du tableau alors que la base ne le contient plus.
    prefs.set(Prefs.LAST_SYNC_AT, 0L)
    prefs.set(Prefs.RECONCILE_TOTALS, "")
    prefs.set(Prefs.RECONCILE_MARKS, "")
    prefs.set(Prefs.RECONCILE_FULL_AT, "")
    prefs.set(Prefs.READLATER_HASHES, "")
    // shortcut: la copie n'est pas réécrite ici — les articles ne sont pas encore revenus, et
    // l'écrire maintenant effacerait les lus/favoris en attente. Elle l'est à la fin de la
    // synchro, une fois la file rejouée ; un article jamais revenu (sorti du flux) perd son
    // état, et il faudrait alors fusionner la file dans l'export.
    true
}
