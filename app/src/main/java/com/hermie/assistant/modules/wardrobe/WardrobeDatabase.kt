package com.hermie.assistant.modules.wardrobe

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * SQLite database for wardrobe items, outfits, and style preferences.
 */
class WardrobeDatabase(context: Context) : SQLiteOpenHelper(
    context, DB_NAME, null, DB_VERSION
) {
    companion object {
        private const val TAG = "WardrobeDB"
        private const val DB_NAME = "hermie_wardrobe.db"
        private const val DB_VERSION = 1

        // Clothing types
        val CLOTHING_TYPES = listOf(
            "pants", "shirts", "tshirts", "jumpers", "hoodies",
            "accessories", "coats", "shoes", "shorts", "skirts", "dresses"
        )

        // Cooldown days per type (before an item can be recommended again)
        val COOLDOWN_DAYS = mapOf(
            "pants" to 2,
            "shirts" to 5,
            "tshirts" to 7,
            "jumpers" to 5,
            "hoodies" to 5,
            "accessories" to 1,
            "coats" to 3,
            "shoes" to 2,
            "shorts" to 3,
            "skirts" to 5,
            "dresses" to 7
        )

        private const val DEFAULT_COOLDOWN_DAYS = 3
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS clothing_items (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                image_path  TEXT NOT NULL,
                color       TEXT,
                pattern     TEXT,
                fabric      TEXT,
                type        TEXT NOT NULL,
                formality   INTEGER NOT NULL DEFAULT 3,
                description TEXT,
                created_at  INTEGER NOT NULL,
                last_worn   INTEGER,
                wear_count  INTEGER NOT NULL DEFAULT 0,
                active      INTEGER NOT NULL DEFAULT 1
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_items_type ON clothing_items(type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_items_active ON clothing_items(active)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS outfits (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                occasion        TEXT,
                weather_summary TEXT,
                created_at      INTEGER NOT NULL,
                is_favorite     INTEGER NOT NULL DEFAULT 0
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS outfit_items (
                outfit_id        INTEGER NOT NULL REFERENCES outfits(id) ON DELETE CASCADE,
                clothing_item_id INTEGER NOT NULL REFERENCES clothing_items(id),
                slot             TEXT NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_oi_outfit ON outfit_items(outfit_id)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS unprocessed_photos (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                image_path TEXT NOT NULL,
                added_at   INTEGER NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS style_profile (
                key   TEXT PRIMARY KEY,
                value TEXT
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations go here
    }

    // ── Unprocessed photos ──────────────────────────────────

    fun addUnprocessedPhoto(imagePath: String) {
        writableDatabase.insert("unprocessed_photos", null, ContentValues().apply {
            put("image_path", imagePath)
            put("added_at", System.currentTimeMillis())
        })
    }

    fun getUnprocessedPhotos(): List<UnprocessedPhoto> {
        val list = mutableListOf<UnprocessedPhoto>()
        readableDatabase.rawQuery(
            "SELECT id, image_path, added_at FROM unprocessed_photos ORDER BY added_at ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(UnprocessedPhoto(
                    id = c.getLong(0),
                    imagePath = c.getString(1),
                    addedAt = c.getLong(2)
                ))
            }
        }
        return list
    }

    fun getUnprocessedCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM unprocessed_photos", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun removeUnprocessedPhoto(id: Long) {
        writableDatabase.delete("unprocessed_photos", "id = ?", arrayOf(id.toString()))
    }

    // ── Clothing items ──────────────────────────────────────

    fun addClothingItem(item: ClothingItem): Long {
        return writableDatabase.insert("clothing_items", null, ContentValues().apply {
            put("image_path", item.imagePath)
            put("color", item.color)
            put("pattern", item.pattern)
            put("fabric", item.fabric)
            put("type", item.type)
            put("formality", item.formality)
            put("description", item.description)
            put("created_at", item.createdAt)
            put("last_worn", item.lastWorn)
            put("wear_count", item.wearCount)
            put("active", if (item.active) 1 else 0)
        })
    }

    fun updateClothingItem(item: ClothingItem) {
        writableDatabase.update("clothing_items", ContentValues().apply {
            put("color", item.color)
            put("pattern", item.pattern)
            put("fabric", item.fabric)
            put("type", item.type)
            put("formality", item.formality)
            put("description", item.description)
            put("active", if (item.active) 1 else 0)
        }, "id = ?", arrayOf(item.id.toString()))
    }

    fun getAllItems(): List<ClothingItem> {
        val list = mutableListOf<ClothingItem>()
        readableDatabase.rawQuery(
            "SELECT * FROM clothing_items WHERE active = 1 ORDER BY type, created_at DESC",
            null
        ).use { c -> while (c.moveToNext()) list.add(cursorToItem(c)) }
        return list
    }

    fun getItemsByType(type: String): List<ClothingItem> {
        val list = mutableListOf<ClothingItem>()
        readableDatabase.rawQuery(
            "SELECT * FROM clothing_items WHERE active = 1 AND type = ? ORDER BY created_at DESC",
            arrayOf(type)
        ).use { c -> while (c.moveToNext()) list.add(cursorToItem(c)) }
        return list
    }

    fun getItemById(id: Long): ClothingItem? {
        readableDatabase.rawQuery(
            "SELECT * FROM clothing_items WHERE id = ?",
            arrayOf(id.toString())
        ).use { c ->
            return if (c.moveToFirst()) cursorToItem(c) else null
        }
    }

    /**
     * Get items available for recommendation (respecting cooldown periods).
     * Each clothing type has a different cooldown before it can be recommended again.
     */
    fun getAvailableItems(): List<ClothingItem> {
        val now = System.currentTimeMillis()
        val list = mutableListOf<ClothingItem>()

        // Build CASE expression for per-type cooldown
        val caseExpr = COOLDOWN_DAYS.entries.joinToString(" ") {
            "WHEN '${it.key}' THEN ${it.value * 86400000L}"
        }
        val defaultCooldown = DEFAULT_COOLDOWN_DAYS * 86400000L

        readableDatabase.rawQuery("""
            SELECT * FROM clothing_items
            WHERE active = 1
              AND (last_worn IS NULL
                   OR last_worn < $now - CASE type $caseExpr ELSE $defaultCooldown END)
            ORDER BY type, RANDOM()
        """, null).use { c ->
            while (c.moveToNext()) list.add(cursorToItem(c))
        }
        return list
    }

    fun markWorn(itemId: Long) {
        writableDatabase.execSQL("""
            UPDATE clothing_items
            SET last_worn = ${System.currentTimeMillis()},
                wear_count = wear_count + 1
            WHERE id = $itemId
        """)
    }

    fun deactivateItem(itemId: Long) {
        writableDatabase.update("clothing_items", ContentValues().apply {
            put("active", 0)
        }, "id = ?", arrayOf(itemId.toString()))
    }

    fun getItemCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM clothing_items WHERE active = 1", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun cursorToItem(c: android.database.Cursor): ClothingItem {
        return ClothingItem(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            imagePath = c.getString(c.getColumnIndexOrThrow("image_path")),
            color = c.getString(c.getColumnIndexOrThrow("color")) ?: "",
            pattern = c.getString(c.getColumnIndexOrThrow("pattern")) ?: "",
            fabric = c.getString(c.getColumnIndexOrThrow("fabric")) ?: "",
            type = c.getString(c.getColumnIndexOrThrow("type")),
            formality = c.getInt(c.getColumnIndexOrThrow("formality")),
            description = c.getString(c.getColumnIndexOrThrow("description")) ?: "",
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            lastWorn = if (c.isNull(c.getColumnIndexOrThrow("last_worn"))) null
                       else c.getLong(c.getColumnIndexOrThrow("last_worn")),
            wearCount = c.getInt(c.getColumnIndexOrThrow("wear_count")),
            active = c.getInt(c.getColumnIndexOrThrow("active")) == 1
        )
    }

    // ── Outfits ─────────────────────────────────────────────

    fun saveOutfit(occasion: String, weatherSummary: String, items: Map<String, Long>): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val outfitId = db.insert("outfits", null, ContentValues().apply {
                put("occasion", occasion)
                put("weather_summary", weatherSummary)
                put("created_at", System.currentTimeMillis())
            })
            for ((slot, itemId) in items) {
                db.insert("outfit_items", null, ContentValues().apply {
                    put("outfit_id", outfitId)
                    put("clothing_item_id", itemId)
                    put("slot", slot)
                })
            }
            db.setTransactionSuccessful()
            return outfitId
        } finally {
            db.endTransaction()
        }
    }

    fun toggleFavorite(outfitId: Long) {
        writableDatabase.execSQL("""
            UPDATE outfits SET is_favorite = CASE WHEN is_favorite = 1 THEN 0 ELSE 1 END
            WHERE id = $outfitId
        """)
    }

    fun getFavoriteOutfits(): List<SavedOutfit> {
        return getOutfitsWhere("is_favorite = 1")
    }

    fun getRecentOutfits(limit: Int = 10): List<SavedOutfit> {
        return getOutfitsWhere("1=1 ORDER BY created_at DESC LIMIT $limit")
    }

    private fun getOutfitsWhere(where: String): List<SavedOutfit> {
        val outfits = mutableListOf<SavedOutfit>()
        readableDatabase.rawQuery(
            "SELECT * FROM outfits WHERE $where",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val outfitId = c.getLong(c.getColumnIndexOrThrow("id"))
                val items = getOutfitItems(outfitId)
                outfits.add(SavedOutfit(
                    id = outfitId,
                    occasion = c.getString(c.getColumnIndexOrThrow("occasion")) ?: "",
                    weatherSummary = c.getString(c.getColumnIndexOrThrow("weather_summary")) ?: "",
                    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                    isFavorite = c.getInt(c.getColumnIndexOrThrow("is_favorite")) == 1,
                    items = items
                ))
            }
        }
        return outfits
    }

    private fun getOutfitItems(outfitId: Long): Map<String, ClothingItem> {
        val items = mutableMapOf<String, ClothingItem>()
        readableDatabase.rawQuery("""
            SELECT oi.slot, ci.* FROM outfit_items oi
            JOIN clothing_items ci ON ci.id = oi.clothing_item_id
            WHERE oi.outfit_id = ?
        """, arrayOf(outfitId.toString())).use { c ->
            while (c.moveToNext()) {
                val slot = c.getString(0)
                // Offset by 1 because slot is column 0
                items[slot] = ClothingItem(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    imagePath = c.getString(c.getColumnIndexOrThrow("image_path")),
                    color = c.getString(c.getColumnIndexOrThrow("color")) ?: "",
                    pattern = c.getString(c.getColumnIndexOrThrow("pattern")) ?: "",
                    fabric = c.getString(c.getColumnIndexOrThrow("fabric")) ?: "",
                    type = c.getString(c.getColumnIndexOrThrow("type")),
                    formality = c.getInt(c.getColumnIndexOrThrow("formality")),
                    description = c.getString(c.getColumnIndexOrThrow("description")) ?: "",
                    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                    lastWorn = if (c.isNull(c.getColumnIndexOrThrow("last_worn"))) null
                               else c.getLong(c.getColumnIndexOrThrow("last_worn")),
                    wearCount = c.getInt(c.getColumnIndexOrThrow("wear_count")),
                    active = c.getInt(c.getColumnIndexOrThrow("active")) == 1
                )
            }
        }
        return items
    }

    // ── Style profile ───────────────────────────────────────

    fun getStyleValue(key: String): String? {
        readableDatabase.rawQuery(
            "SELECT value FROM style_profile WHERE key = ?",
            arrayOf(key)
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun setStyleValue(key: String, value: String) {
        writableDatabase.insertWithOnConflict("style_profile", null, ContentValues().apply {
            put("key", key)
            put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Get IDs of clothing items that appear in favorited outfits. */
    fun getFavoriteItemIds(): Set<Long> {
        val ids = mutableSetOf<Long>()
        readableDatabase.rawQuery("""
            SELECT DISTINCT oi.clothing_item_id FROM outfit_items oi
            JOIN outfits o ON o.id = oi.outfit_id
            WHERE o.is_favorite = 1
        """, null).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        return ids
    }

    fun getChoiceCount(): Int = getStyleValue("total_choices")?.toIntOrNull() ?: 0
    fun getRejectionCount(): Int = getStyleValue("total_rejections")?.toIntOrNull() ?: 0
    fun getStyleDescription(): String? = getStyleValue("style_description")
}

// ── Data classes ─────────────────────────────────────────────

data class ClothingItem(
    val id: Long = 0,
    val imagePath: String,
    val color: String = "",
    val pattern: String = "",
    val fabric: String = "",
    val type: String,
    val formality: Int = 3,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastWorn: Long? = null,
    val wearCount: Int = 0,
    val active: Boolean = true
)

data class UnprocessedPhoto(
    val id: Long,
    val imagePath: String,
    val addedAt: Long
)

data class SavedOutfit(
    val id: Long,
    val occasion: String,
    val weatherSummary: String,
    val createdAt: Long,
    val isFavorite: Boolean,
    val items: Map<String, ClothingItem>
)

data class OutfitSuggestion(
    val items: Map<String, ClothingItem>,
    val reasoning: String
)
