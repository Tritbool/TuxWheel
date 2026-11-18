package io.github.tuxwheel.settings

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.material3.RichText
import io.github.tuxwheel.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThirdPartyLicensesDialog(onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val externalUriHandler = LocalUriHandler.current
    var selectedAsset by rememberSaveable { mutableStateOf<String?>(null) }
    val documents by produceState<Map<String, String>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            listOf(
                "third_party_licenses.md",
                "NOTICE.txt",
                "GPL-3.0.txt",
            ).associateWith { name ->
                context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.third_party_licenses_title)) },
                    actions = {
                        TextButton(onClick = onDismissRequest) {
                            Text(stringResource(android.R.string.ok))
                        }
                    },
                )
            },
        ) { padding ->
            val loadedDocuments = documents
            if (loadedDocuments == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    loadedDocuments.filterKeys { it != "third_party_licenses.md" }.forEach { (name, text) ->
                        item(key = name) { LicenseNotice(name, text) }
                    }
                    item(key = "dependencies") {
                        SelectionContainer {
                            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                                override fun openUri(uri: String) {
                                    val assetPath = licenseAssetPath(uri)
                                    if (assetPath != null) {
                                        selectedAsset = assetPath
                                    } else if (Uri.parse(uri).scheme in listOf("https", "http")) {
                                        try {
                                            externalUriHandler.openUri(uri)
                                        } catch (_: ActivityNotFoundException) {
                                            // A browser is optional on offline devices.
                                        } catch (_: IllegalArgumentException) {
                                            // Ignore malformed URLs in dependency metadata.
                                        }
                                    }
                                }
                            }) {
                                RichText(modifier = Modifier.fillMaxWidth()) {
                                    Markdown(loadedDocuments.getValue("third_party_licenses.md"))
                                }
                            }
                        }
                    }
                }
            }
        }
        selectedAsset?.let { path ->
            LicenseAssetDialog(path, onDismissRequest = { selectedAsset = null })
        }
    }
}

internal fun licenseAssetPath(url: String): String? {
    val uri = Uri.parse(url)
    val path = uri.path ?: return null
    if (uri.scheme != null || uri.authority != null || path.startsWith("/") ||
        path.isBlank() || path.split('/').any { it == ".." || it == "." }
    ) return null
    return "licenses/$path"
}

@Composable
private fun LicenseAssetDialog(path: String, onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val errorText = stringResource(R.string.third_party_license_read_error)
    val text by produceState<String?>(null, context, path) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open(path).bufferedReader().use { it.readText() }
            } catch (_: IOException) {
                errorText
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(path.substringAfterLast('/')) },
        text = {
            val loadedText = text
            if (loadedText == null) {
                CircularProgressIndicator()
            } else {
                SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                    Text(loadedText)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

@Composable
private fun LicenseNotice(name: String, text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }
        if (expanded) {
            SelectionContainer {
                Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
