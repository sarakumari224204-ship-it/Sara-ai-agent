package com.saraai.offline.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "instructions")
data class InstructionEntity(
    @PrimaryKey val id: String,
    val text: String,
    val version: Int,
    val createdAt: Long,
    val enabled: Boolean = true,
    val priority: Int = 0,
)

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val kind: String,
    val body: String,
    val createdAt: Long,
    val category: String = "other",
    val enabled: Boolean = true,
)

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val gender: String = "",
    val age: Int = 0,
    val city: String = "",
    val state: String = "",
    val education: String = "",
    val occupation: String = "",
    val religion: String = "",
    val caste: String = "",
    val maritalStatus: String = "",
    val height: String = "",
    val income: String = "",
    val expectations: String = "",
    val family: String = "",
    val contact: String = "",
    val raw: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface BureauDao {
    @Query("SELECT * FROM instructions ORDER BY priority DESC, createdAt DESC")
    suspend fun instructions(): List<InstructionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInstruction(row: InstructionEntity)

    @Query("DELETE FROM instructions WHERE id = :id")
    suspend fun deleteInstruction(id: String)

    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    suspend fun documents(): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(row: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    @Query("DELETE FROM documents")
    suspend fun clearDocuments()

    @Query("DELETE FROM instructions")
    suspend fun clearInstructions()

    @Query("SELECT * FROM profiles ORDER BY createdAt DESC")
    suspend fun profiles(): List<ProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(row: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfiles(rows: List<ProfileEntity>)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfile(id: String)

    @Query("DELETE FROM profiles")
    suspend fun clearProfiles()
}

@Database(
    entities = [InstructionEntity::class, DocumentEntity::class, ProfileEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class BureauDb : RoomDatabase() {
    abstract fun dao(): BureauDao

    companion object {
        @Volatile private var instance: BureauDb? = null

        fun open(context: Context): BureauDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BureauDb::class.java,
                    "sara-bureau.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
