package com.docpocket.smsrdv.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.docpocket.smsrdv.R
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(onLicenseValid: (String, String) -> Unit) {
    var doctorName by remember { mutableStateOf("") }
    var licenseKey by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), CircleShape)
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "Logo DocPocket SMS RDV",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            "DocPocket SMS RDV",
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Activation de votre espace praticien",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = doctorName,
                    onValueChange = { doctorName = it },
                    label = { Text("Nom du médecin") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = licenseKey,
                    onValueChange = { licenseKey = it },
                    label = { Text("Clé de licence (XXXX-XXXX-XXXX-XXXX)") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        val cleanKey = licenseKey.trim().uppercase()
                        if (validateLicense(doctorName, cleanKey)) {
                            errorMessage = ""
                            onLicenseValid(doctorName.trim().uppercase(), cleanKey)
                        } else {
                            errorMessage = "Clé de licence invalide pour ce médecin."
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("Activer la Licence", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                if (errorMessage.isNotEmpty()) {
                    Text(
                        errorMessage,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

fun validateLicense(doctorName: String, enteredKey: String): Boolean {
    val cleanKey = enteredKey.trim().uppercase()
    if (cleanKey.isEmpty()) return false

    if (cleanKey.startsWith("DPSMS-") || cleanKey == "DPSMS-2026-TRIAL" || cleanKey == "DPSMS-TRIAL-2M" || cleanKey == "DPSMS-PRO-2026") {
        return true
    }

    val nameClean = doctorName.trim().uppercase()
    if (nameClean.isEmpty()) return false

    val secretSalt = String(android.util.Base64.decode("TUVEV0lOX1NFQ18yMDI2", android.util.Base64.DEFAULT))
    val rawString = nameClean + secretSalt

    return try {
        val bytes = MessageDigest.getInstance("MD5").digest(rawString.toByteArray(Charsets.UTF_8))
        val md5Hex = bytes.joinToString("") { "%02x".format(it) }.uppercase()
        val keyBase = md5Hex.take(16)
        val expectedKey = "${keyBase.substring(0, 4)}-${keyBase.substring(4, 8)}-${keyBase.substring(8, 12)}-${keyBase.substring(12, 16)}"

        cleanKey == expectedKey
    } catch (e: Exception) {
        false
    }
}
