package com.mangareader.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "manga_books",
    indices = [
        Index("isHidden"),
        Index("isHidden", "lastReadAt"),
        Index("isHidden", "isFavorite"),
        Index("filePath"),
        Index("lastReadAt")
    ]
)
@Immutable
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,
    val fileType: String = "",
    val coverPath: String? = null,
    val totalPages: Int = 0,
    val readPages: Int = 0,
    val lastReadAt: Long? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val source: String = "local"
)

@Entity(tableName = "imported_folders")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val treeUri: String,
    val displayName: String = "",
    val lastScanAt: Long = 0
)

@Entity(tableName = "bookshelves")
@Immutable
data class Bookshelf(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
)

@Entity(
    tableName = "bookshelf_items",
    foreignKeys = [ForeignKey(entity = Bookshelf::class, parentColumns = ["id"], childColumns = ["bookshelfId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookshelfId"), Index("bookId")]
)
data class BookshelfItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookshelfId: Long,
    val bookId: Long
)

@Entity(tableName = "tags")
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long = 0xFF7C8CF8
)

@Entity(
    tableName = "tag_links",
    foreignKeys = [
        ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("tagId"), Index("bookId")]
)
data class TagLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tagId: Long,
    val bookId: Long
)

@Entity(tableName = "reading_history",
    indices = [Index("bookId"), Index("timestamp")])
data class ReadingHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val pagesRead: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val scrollX: Int = 0,
    val scrollY: Int = 0,
    val zoomLevel: Float = 1.0f
)

@Dao
interface MangaDao {
    @Query("SELECT * FROM manga_books ORDER BY lastReadAt DESC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isFavorite = 1 ORDER BY lastReadAt DESC")
    fun getFavoriteBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE title LIKE '%' || :query || '%' ORDER BY lastReadAt DESC")
    fun searchBooks(query: String): Flow<List<Book>>

    @Query("SELECT * FROM manga_books ORDER BY title ASC")
    fun getAllBooksByName(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books ORDER BY addedAt DESC")
    fun getAllBooksByDateAdded(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE id = :id")
    suspend fun getBookById(id: Long): Book?

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 ORDER BY lastReadAt DESC")
    fun getVisibleBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 1 ORDER BY lastReadAt DESC")
    fun getHiddenBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND title LIKE '%' || :query || '%' ORDER BY lastReadAt DESC")
    fun searchVisibleBooks(query: String): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND isFavorite = 1 AND title LIKE '%' || :query || '%' ORDER BY lastReadAt DESC")
    fun searchFavoriteBooks(query: String): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND (readPages > 0 OR lastReadAt IS NOT NULL) AND title LIKE '%' || :query || '%' ORDER BY lastReadAt DESC")
    fun searchReadingBooks(query: String): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND readPages = 0 AND lastReadAt IS NULL AND title LIKE '%' || :query || '%' ORDER BY lastReadAt DESC")
    fun searchUnreadBooks(query: String): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND totalPages > 0 AND readPages >= totalPages AND title LIKE '%' || :query || '%' ORDER BY lastReadAt DESC")
    fun searchFinishedBooks(query: String): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND fileType = :type AND title LIKE '%' || :query || '%' ORDER BY lastReadAt DESC")
    fun searchByType(query: String, type: String): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 ORDER BY lastReadAt DESC")
    suspend fun getAllBooksList(): List<Book>

    @Query("SELECT DISTINCT fileType FROM manga_books WHERE isHidden = 0")
    suspend fun getAllFileTypes(): List<String>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 ORDER BY title ASC")
    fun getVisibleBooksByName(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 ORDER BY addedAt DESC")
    fun getVisibleBooksByDateAdded(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 ORDER BY CASE WHEN totalPages > 0 THEN cast(readPages as float) / totalPages ELSE 0 END DESC")
    fun getVisibleBooksByProgress(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND isFavorite = 1 ORDER BY lastReadAt DESC")
    fun getVisibleFavoriteBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND totalPages > 0 AND readPages >= totalPages ORDER BY lastReadAt DESC")
    fun getVisibleFinishedBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND (readPages > 0 OR lastReadAt IS NOT NULL) AND (totalPages = 0 OR readPages < totalPages) ORDER BY lastReadAt DESC")
    fun getVisibleReadingBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE isHidden = 0 AND readPages = 0 AND lastReadAt IS NULL ORDER BY lastReadAt DESC")
    fun getVisibleUnreadBooks(): Flow<List<Book>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBook(book: Book): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBooksBatch(books: List<Book>)

    @Update
    suspend fun updateBook(book: Book)

    @Delete
    suspend fun deleteBook(book: Book)

    @Query("UPDATE manga_books SET readPages = :pages, lastReadAt = :timestamp WHERE id = :id")
    suspend fun updateReadingProgress(id: Long, pages: Int, timestamp: Long)

    @Query("UPDATE manga_books SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE manga_books SET isHidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE manga_books SET isHidden = :hidden")
    suspend fun setHiddenAll(hidden: Boolean)

    @Query("SELECT * FROM manga_books WHERE filePath LIKE 'a:' || :auth || '|' || :treeDocId || '|%' OR filePath LIKE 'd:' || :auth || '|' || :treeDocId || '%' OR filePath LIKE 'z:a:' || :auth || '|' || :treeDocId || '|%'")
    suspend fun getBooksByFolderParams(auth: String, treeDocId: String): List<Book>

    @Query("UPDATE manga_books SET isHidden = :hidden WHERE filePath LIKE 'a:' || :auth || '|' || :treeDocId || '|%' OR filePath LIKE 'd:' || :auth || '|' || :treeDocId || '%' OR filePath LIKE 'z:a:' || :auth || '|' || :treeDocId || '|%'")
    suspend fun setHiddenByFolderParams(auth: String, treeDocId: String, hidden: Boolean)

    @Query("SELECT * FROM manga_books WHERE filePath = :path LIMIT 1")
    suspend fun getBookByPath(path: String): Book?

    @Query("DELETE FROM manga_books WHERE filePath LIKE '%' || :treeUriStr || '%'")
    suspend fun deleteBooksByFolder(treeUriStr: String)

    @Query("DELETE FROM manga_books WHERE (filePath LIKE 'a:' || :auth || '|' || :treeDocId || '|%' OR filePath LIKE 'd:' || :auth || '|' || :treeDocId || '%' OR filePath LIKE 'z:a:' || :auth || '|' || :treeDocId || '|%')")
    suspend fun deleteBooksByFolderParams(auth: String, treeDocId: String)

    @Query("SELECT COUNT(*) FROM manga_books WHERE (filePath LIKE 'a:' || :auth || '|' || :treeDocId || '|%' OR filePath LIKE 'd:' || :auth || '|' || :treeDocId || '%' OR filePath LIKE 'z:a:' || :auth || '|' || :treeDocId || '|%')")
    suspend fun countBooksByFolder(auth: String, treeDocId: String): Int

    @Query("SELECT * FROM manga_books WHERE totalPages > 0 AND readPages >= totalPages ORDER BY lastReadAt DESC")
    fun getFinishedBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE lastReadAt IS NOT NULL AND (totalPages = 0 OR readPages < totalPages) ORDER BY lastReadAt DESC")
    fun getReadingBooks(): Flow<List<Book>>

    @Query("SELECT * FROM manga_books WHERE (totalPages = 0 OR readPages = 0) AND isFavorite = 0 ORDER BY lastReadAt DESC")
    fun getUnreadBooks(): Flow<List<Book>>

    @Query("SELECT * FROM imported_folders ORDER BY displayName")
    fun getAllFolders(): Flow<List<Folder>>

    @Query("SELECT * FROM imported_folders ORDER BY displayName")
    suspend fun getAllFoldersList(): List<Folder>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: Folder): Long

    @Update
    suspend fun updateFolder(folder: Folder)

    @Delete
    suspend fun deleteFolder(folder: Folder)

    @Query("SELECT * FROM imported_folders WHERE treeUri = :uri LIMIT 1")
    suspend fun getFolderByUri(uri: String): Folder?

    @Query("SELECT COUNT(*) FROM manga_books WHERE isHidden = 1")
    suspend fun countHidden(): Int

    @Query("SELECT COUNT(*) FROM manga_books")
    suspend fun countAll(): Int

    @Query("SELECT COALESCE(SUM(totalPages), 0) FROM manga_books")
    suspend fun countTotalPages(): Int

    @Query("SELECT COUNT(*) FROM manga_books WHERE readPages > 0")
    suspend fun countReadBooks(): Int

    @Insert
    suspend fun insertHistory(history: ReadingHistory)

    @Query("SELECT * FROM reading_history WHERE bookId = :bookId ORDER BY timestamp DESC LIMIT 10")
    fun getBookHistory(bookId: Long): Flow<List<ReadingHistory>>

    @Query("SELECT SUM(pagesRead) FROM reading_history")
    suspend fun totalPagesRead(): Int

    @Query("SELECT COUNT(DISTINCT bookId) FROM reading_history")
    suspend fun uniqueBooksRead(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookshelf(bookshelf: Bookshelf): Long

    @Update
    suspend fun updateBookshelf(bookshelf: Bookshelf)

    @Query("UPDATE bookshelves SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateBookshelfSort(id: Long, sortOrder: Int)

    @Delete
    suspend fun deleteBookshelf(bookshelf: Bookshelf)

    @Query("SELECT * FROM bookshelves ORDER BY sortOrder ASC, createdAt DESC")
    fun getAllBookshelves(): Flow<List<Bookshelf>>

    @Query("SELECT * FROM bookshelves ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun getAllBookshelvesList(): List<Bookshelf>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookshelfItem(item: BookshelfItem): Long

    @Delete
    suspend fun deleteBookshelfItem(item: BookshelfItem)

    @Query("DELETE FROM bookshelf_items WHERE bookshelfId = :bookshelfId AND bookId = :bookId")
    suspend fun removeBookFromShelf(bookshelfId: Long, bookId: Long)

    @Query("SELECT * FROM bookshelf_items WHERE bookshelfId = :bookshelfId")
    fun getBooksInShelf(bookshelfId: Long): Flow<List<BookshelfItem>>

    @Query("SELECT * FROM bookshelf_items WHERE bookshelfId = :bookshelfId")
    suspend fun getBooksInShelfList(bookshelfId: Long): List<BookshelfItem>

    @Query("SELECT * FROM bookshelf_items WHERE bookId = :bookId")
    suspend fun getShelvesForBook(bookId: Long): List<BookshelfItem>

    @Query("SELECT b.* FROM manga_books b INNER JOIN bookshelf_items bi ON b.id = bi.bookId WHERE bi.bookshelfId = :bookshelfId ORDER BY b.title ASC")
    fun getBooksByShelf(bookshelfId: Long): Flow<List<Book>>

    @Query("DELETE FROM manga_books WHERE id IN (:ids)")
    suspend fun deleteBooksByIds(ids: List<Long>)

    @Query("DELETE FROM bookshelf_items WHERE bookId IN (:bookIds)")
    suspend fun removeBooksFromAllShelves(bookIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: Tag): Long

    @Delete
    suspend fun deleteTag(tag: Tag)

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun getAllTags(): Flow<List<Tag>>

    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun getAllTagsList(): List<Tag>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTagLink(link: TagLink): Long

    @Delete
    suspend fun deleteTagLink(link: TagLink)

    @Query("DELETE FROM tag_links WHERE bookId = :bookId")
    suspend fun removeAllTagsFromBook(bookId: Long)

    @Query("SELECT t.* FROM tags t INNER JOIN tag_links tl ON t.id = tl.tagId WHERE tl.bookId = :bookId")
    fun getTagsForBook(bookId: Long): Flow<List<Tag>>

    @Query("SELECT t.* FROM tags t INNER JOIN tag_links tl ON t.id = tl.tagId WHERE tl.bookId = :bookId")
    suspend fun getTagsForBookList(bookId: Long): List<Tag>

    @Query("SELECT b.* FROM manga_books b INNER JOIN tag_links tl ON b.id = tl.bookId WHERE tl.tagId = :tagId AND b.isHidden = 0 ORDER BY b.title ASC")
    fun getBooksByTag(tagId: Long): Flow<List<Book>>

    @Query("SELECT COUNT(*) FROM tag_links WHERE tagId = :tagId")
    suspend fun countBooksInTag(tagId: Long): Int
}

@Database(entities = [Book::class, Folder::class, ReadingHistory::class, Bookshelf::class, BookshelfItem::class, Tag::class, TagLink::class], version = 9, exportSchema = false)
abstract class MangaDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao

    companion object {
        @Volatile
        private var INSTANCE: MangaDatabase? = null

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS tags (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, color INTEGER NOT NULL DEFAULT 8134264)")
                database.execSQL("CREATE TABLE IF NOT EXISTS tag_links (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, tagId INTEGER NOT NULL, bookId INTEGER NOT NULL, FOREIGN KEY (tagId) REFERENCES tags(id) ON DELETE CASCADE, FOREIGN KEY (bookId) REFERENCES manga_books(id) ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_tag_links_tagId ON tag_links(tagId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_tag_links_bookId ON tag_links(bookId)")
            }
        }

        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE reading_history ADD COLUMN scrollX INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE reading_history ADD COLUMN scrollY INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE reading_history ADD COLUMN zoomLevel REAL NOT NULL DEFAULT 1.0")
            }
        }

        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS index_manga_books_isHidden ON manga_books(isHidden)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_manga_books_isHidden_lastReadAt ON manga_books(isHidden, lastReadAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_manga_books_isHidden_isFavorite ON manga_books(isHidden, isFavorite)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_manga_books_filePath ON manga_books(filePath)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_manga_books_lastReadAt ON manga_books(lastReadAt)")
            }
        }

        fun getDatabase(context: Context): MangaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MangaDatabase::class.java,
                    "manga_reader_db"
                ).addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
