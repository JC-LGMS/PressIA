package com.pressia.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pressia.data.PressRepository
import com.pressia.data.isLocalProvider
import com.pressia.data.local.SubscriptionEntity

/**
 * Drawer de PressIA.
 *
 * Trois principes retenus pour cette refonte :
 *  - **tout est scrollable**, y compris l'en-tête et les actions : un élément fixe en bas rendait
 *    le reste inaccessible sur un écran court ;
 *  - **la taille du texte des flux est réglable** ([com.pressia.data.local.Prefs.DRAWER_TEXT_SCALE]),
 *    100 % par défaut, donc aucun changement pour qui ne touche à rien ;
 *  - **un dossier replié garde son espace** : sans ligne d'air, les éléments voisins remontent et
 *    l'utilisateur a l'impression qu'un élément a disparu de la liste.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PressDrawer(vm: PressVM, currentRoute: String, onNavigate: (String) -> Unit, onClose: () -> Unit) {
    // [PressVM.folders], [PressVM.subs] et les compteurs sont des états Compose : les lire
    // directement suffit à redessiner le drawer quand une synchronisation les modifie.
    val folders = vm.folders
    val subs = vm.subs
    val collapsed = vm.collapsedFolders
    val unreads = vm.unreads
    val folderUnreads = vm.folderUnreads
    val textScale by vm.drawerScale.collectAsState()

    // Un seul menu contextuel pour toute la colonne : chaque élément est désigné par une clé
    // composite, ce qui évite d'empiler un état par dossier et par flux.

    // 100 % = l'ancien 125 % : bases relevées d'un quart (flux 17,5 sp, dossier 18,75 sp).
    val feedSp = (17.5f * textScale / 100f).coerceIn(11f, 25f)
    val folderSp = (18.75f * textScale / 100f).coerceIn(12f, 27f)
    // Numéro de version affiché en bas du drawer, en petit.
    val ctx = LocalContext.current
    val version = remember { appVersionName(ctx) }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(292.dp)
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(top = 10.dp, bottom = 24.dp)
        ) {
            // ---- en-tête : icône de l'application, nom, puis actions en haut à droite ------
            // Le compteur de non-lus a été retiré : il était le seul élément de la colonne à ne
            // pas être une action, et chaque flux porte déjà son propre compteur.
            val appearance by vm.appearance.collectAsState()
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(com.pressia.R.drawable.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(7.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "PressIA", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                AppearanceButton(Icons.Filled.BrightnessAuto, "Apparence automatique", appearance == "auto") {
                    vm.setAppearance("auto")
                }
                AppearanceButton(Icons.Filled.LightMode, "Apparence jour", appearance == "light") {
                    vm.setAppearance("light")
                }
                AppearanceButton(Icons.Filled.DarkMode, "Apparence nuit", appearance == "night") {
                    vm.setAppearance("night")
                }

            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))

            // ---- navigation principale ------------------------------------------------------
            DrawerRow(Icons.Filled.Home, "Accueil", currentRoute == "home", feedSp) {
                onNavigate("home"); onClose()
            }
            // La liste à plat de tous les flux n'était atteignable que par le bouton retour depuis
            // un dossier : elle a maintenant sa propre entrée.
            DrawerRow(
                Icons.Filled.List, "Tous les articles",
                currentRoute == "stream" && vm.scope.feedId == null && vm.scope.categoryId == null, feedSp
            ) {
                vm.switchScope(Scope(label = "Tous les articles"))
                onNavigate("stream"); onClose()
            }
            DrawerRow(Icons.Filled.Star, "Favoris", currentRoute == "starred", feedSp) {
                onNavigate("starred"); onClose()
            }
            DrawerRow(Icons.Filled.ContentCopy, "Doublons", currentRoute == "dedup", feedSp) {
                onNavigate("dedup"); onClose()
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            DrawerRow(Icons.Filled.Search, "Recherche", currentRoute == "search", feedSp) {
                onNavigate("search"); onClose()
            }
            DrawerRow(Icons.Filled.BarChart, "Stats", currentRoute == "stats", feedSp) {
                onNavigate("stats"); onClose()
            }
            DrawerRow(Icons.Filled.Settings, "Réglages", currentRoute == "settings", feedSp) {
                onNavigate("settings"); onClose()
            }
            DrawerRow(Icons.Filled.AccountCircle, "Comptes", currentRoute == "login", feedSp) {
                onNavigate("login"); onClose()
            }
            DrawerRow(Icons.Filled.Save, "Sauvegarde/Restauration", currentRoute == "backup", feedSp) {
                onNavigate("backup"); onClose()
            }
            DrawerRow(Icons.Filled.Info, "À propos", currentRoute == "about", feedSp) {
                onNavigate("about"); onClose()
            }
            if (!version.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Text(
                    "v$version",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}


@Composable
private fun DrawerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    fontSize: Float,
    onClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontSize = fontSize.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AppearanceButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = { onClick() }) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}
