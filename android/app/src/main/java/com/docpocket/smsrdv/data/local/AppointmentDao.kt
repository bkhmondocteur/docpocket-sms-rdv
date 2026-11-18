package com.docpocket.smsrdv.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppointmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppointment(appointment: AppointmentEntity): Long

    @Update
    suspend fun updateAppointment(appointment: AppointmentEntity)

    @Query("SELECT * FROM RendezVous WHERE id = :id LIMIT 1")
    suspend fun getAppointmentById(id: Int): AppointmentEntity?

    @Query("SELECT * FROM RendezVous WHERE firebaseKey = :key LIMIT 1")
    suspend fun getAppointmentByFirebaseKey(key: String): AppointmentEntity?

    @Query("SELECT * FROM RendezVous ORDER BY dateRdv ASC, heureRdv ASC")
    fun getAllAppointments(): Flow<List<AppointmentEntity>>

    @Query("SELECT * FROM RendezVous WHERE dateRdv <= :date AND smsEnvoye = 0")
    suspend fun getAppointmentsForDateNotSent(date: String): List<AppointmentEntity>

    @Query("SELECT * FROM RendezVous WHERE dateRdv >= :minDate AND dateRdv <= :maxDate AND smsEnvoye = 0 ORDER BY dateRdv ASC, heureRdv ASC")
    suspend fun getAppointmentsForDateRangeNotSent(minDate: String, maxDate: String): List<AppointmentEntity>

    @Query("SELECT * FROM RendezVous WHERE smsEnvoye = 0 ORDER BY dateRdv ASC, heureRdv ASC")
    suspend fun getUnsentAppointments(): List<AppointmentEntity>

    @Query("UPDATE RendezVous SET smsEnvoye = 1 WHERE id = :id")
    suspend fun markSmsAsSent(id: Int)

    /**
     * Verrou atomique SQLite anti-doublon :
     * Met à jour smsEnvoye de 0 à 1 UNIQUEMENT si le SMS n'a pas encore été envoyé.
     * Renvoie 1 si le verrou a été obtenu par ce thread/processus, 0 sinon.
     */
    @Query("UPDATE RendezVous SET smsEnvoye = 1 WHERE id = :id AND smsEnvoye = 0")
    suspend fun claimAppointmentForSending(id: Int): Int

    @Query("UPDATE RendezVous SET smsEnvoye = 0 WHERE id = :id")
    suspend fun resetSmsStatus(id: Int)
    
    @Query("DELETE FROM RendezVous WHERE id = :id")
    suspend fun deleteAppointment(id: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppointments(appointments: List<AppointmentEntity>)

    @Query("SELECT * FROM RendezVous ORDER BY id ASC")
    suspend fun getAllAppointmentsList(): List<AppointmentEntity>

    @Query("DELETE FROM RendezVous")
    suspend fun deleteAllAppointments()
}
