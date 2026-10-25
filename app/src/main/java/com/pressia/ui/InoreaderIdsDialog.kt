package com.pressia.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pressia.data.remote.InoreaderAuth
import com.pressia.data.remote.inoreaderAuth

/**
 * Identifiants Inoreader employés par l'application : **App ID**, **App Key** et
 * **AUTH** (l'authentification du compte connecté — jeton d'accès OAuth ou cookie de
 * session web).
 *
 * Les trois sont modifiables : l'App ID et l'App Key servent à l'« authentification
 * applicative » (exigée par l'API sur chaque appel), l'AUTH porte le jeton de session du
 * compte. Les valeurs secrètes sont masquées par défaut — un coup d'œil sur l'écran
 * d'un autre ne doit pas suffire à lire un jeton.
 *
 * L'en-tête réellement envoyé est rappelé sous le champ : c'est lui qui compte, et sa
 * forme dépend du type de compte (`Authorization: GoogleLogin auth=…` pour un jeton
 * OAuth, `Cookie: al=…` pour une session capturée dans la WebView).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InoreaderIdsDialog(
    appId: String,
    appKey: String,
    auth: String,
    hasAccount: Boolean,
    onDismiss: () -> Unit,
    onSave: (appId: String, appKey: String, auth: String) -> Unit
) {
    var id by remember { mutableStateOf(appId) }
    var key by remember { mutableStateOf(appKey) }
    var token by remember { mutableStateOf(auth) }
    var showKey by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    // Forme d'authentification déduite du champ : le rappel de l'en-tête envoyé et le
    // libellé s'y adaptent (jeton OAuth ou cookie de session web).
    val cred = inoreaderAuth(token)
    val isCookie = cred is InoreaderAuth.WebCookie

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Identifiants Inoreader") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Valeurs employées par PressIA pour interroger l'API Inoreader. " +
                        "Elles se remplacent : un compte Inoreader enregistré par vous, ou " +
                        "les valeurs de l'extension officielle du navigateur.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("App ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("App Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton({ showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                if (showKey) "masquer l'App Key" else "montrer l'App Key",
                                Modifier.size(18.dp)
                            )
                        }
                    }
                )
                if (hasAccount) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text(if (isCookie) "AUTH — cookie de session" else "AUTH — jeton d'accès") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        visualTransformation = if (showToken) androidx.compose.ui.text.input.VisualTransformation.None
                        else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton({ showToken = !showToken }) {
                                Icon(
                                    if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    if (showToken) "masquer le jeton" else "montrer le jeton",
                                    Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                    // L'en-tête réellement envoyé : c'est lui qui compte.
                    Text(
                        "En-tête envoyé :",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SelectionContainer {
                        Text(
                            when (cred) {
                                is InoreaderAuth.WebCookie -> "Cookie: " + cred.cookie
                                is InoreaderAuth.OAuth -> "Authorization: GoogleLogin auth=" + cred.token
                                null -> "…"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "Remplacé seulement s'il est accepté par Inoreader et qu'il " +
                            "appartient bien à ce compte.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Aucun compte Inoreader connecté : l'AUTH n'apparaît qu'une fois la " +
                            "connexion faite (Comptes → « Se connecter avec Inoreader »).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton({
                onSave(id.trim(), key.trim(), token.trim())
                onDismiss()
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Annuler") } }
    )
}
