package com.docpocket.smsrdv.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "RendezVous")
data class AppointmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val nomPatient: String,
    val telephone: String,
    val dateRdv: String, // Format AAAA-MM-JJ
    val heureRdv: String, // Format HH:MM
    val texteSms: String,
    val smsEnvoye: Boolean = false,
    val email: String? = null,
    val language: String = "fr", // "fr", "ar", "en"
    val firebaseKey: String? = null,
    val emailEnvoye: Boolean = false,
    val synced: Boolean = false
)
