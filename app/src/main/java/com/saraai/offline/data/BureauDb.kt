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
)

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val kind: String,
    val body: String,
    val createdAt: Long,
)

@Dao
interface BureauDao {
    @Query("SELECT * FROM instructions ORDER BY createdAt DESC")
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

    @Query("DELETE FROM instructions")
    suspend fun clearInstructions()

    @Query("DELETE FROM documents")
    suspend fun clearDocuments()
}

@Database(entities = [InstructionEntity::class, DocumentEntity::class], version = 1, exportSchema = false)
abstract class BureauDb : RoomDatabase() {
    abstract fun dao(): BureauDao

    companion object {
        fun open(context: Context): BureauDb =
            Room.databaseBuilder(context.applicationContext, BureauDb::class.java, "sara-bureau.db").build()
    }
}
