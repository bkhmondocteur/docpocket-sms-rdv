package com.docpocket.smsrdv.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(onLicenseValid: (String) -> Unit) {
    var doctorName by remember { mutableStateOf("") }
    var licenseKey by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id = com.docpocket.smsrdv.R.drawable.app_logo),
            contentDescription = "Logo DocPocket SMS RDV",
            modifier = Modifier.size(120.dp).padding(bottom = 8.dp)
        )
        Text("DocPocket SMS RDV", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(6.dp))
        Text("Activation requise", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        
        Spacer(modifier = Modifier.height(28.dp))

        OutlinedTextField(
            value = doctorName,
            onValueChange = { doctorName = it },
            label = { Text("Nom du médecin") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = licenseKey,
            onValueChange = { licenseKey = it },
            label = { Text("Clé de licence (XXXX-XXXX-XXXX-XXXX)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (validateLicense(doctorName, licenseKey)) {
                    errorMessage = ""
                    onLicenseValid(doctorName.trim().uppercase())
                } else {
                    errorMessage = "Clé de licence invalide pour ce médecin."
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Activer", fontSize = 18.sp)
        }

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
    }
}

fun validateLicense(doctorName: String, enteredKey: String): Boolean {
    val nameClean = doctorName.trim().uppercase()
    if (nameClean.isEmpty() || enteredKey.isEmpty()) return false
    
    val secretSalt = "MEDWIN_SEC_2026"
    val rawString = nameClean + secretSalt
    
    try {
        val bytes = MessageDigest.getInstance("MD5").digest(rawString.toByteArray(Charsets.UTF_8))
        val md5Hex = bytes.joinToString("") { "%02x".format(it) }.uppercase()
        val keyBase = md5Hex.take(16)
        val expectedKey = "${keyBase.substring(0, 4)}-${keyBase.substring(4, 8)}-${keyBase.substring(8, 12)}-${keyBase.substring(12, 16)}"
        
        return enteredKey.trim() == expectedKey
    } catch (e: Exception) {
        return false
    }
}
