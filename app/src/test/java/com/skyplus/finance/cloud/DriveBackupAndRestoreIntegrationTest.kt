package com.skyplus.finance.cloud

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.skyplus.finance.data.backup.DriveHttpResponse
import com.skyplus.finance.data.backup.DriveHttpTransport
import com.skyplus.finance.data.backup.GoogleDriveBackupManager
import com.skyplus.finance.data.database.DatabaseInitializer
import com.skyplus.finance.data.database.SkyPlusDatabase
import com.skyplus.finance.data.entities.EmiPaymentEntity
import com.skyplus.finance.data.entities.LoanEntity
import com.skyplus.finance.data.entities.ShoppingItemEntity
import com.skyplus.finance.data.entities.ShoppingListEntity
import com.skyplus.finance.data.entities.TransactionEntity
import com.skyplus.finance.data.export.FinancialExportManager
import com.skyplus.finance.data.repository.OfflineFinanceRepository
import com.skyplus.finance.data.security.AppPreferencesDataStore
import com.skyplus.finance.data.security.BackupCryptoManager
import com.skyplus.finance.domain.calculator.LoanEmiCalculator
import com.skyplus.finance.domain.model.LoanStatus
import com.skyplus.finance.domain.model.TransactionType
import java.io.File
import java.math.BigDecimal
import java.net.URLDecoder
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DriveBackupAndRestoreIntegrationTest {

  private lateinit var context: Context
  private lateinit var db: SkyPlusDatabase
  private lateinit var repository: OfflineFinanceRepository
  private lateinit var preferencesDataStore: AppPreferencesDataStore
  private lateinit var exportManager: FinancialExportManager
  private lateinit var fakeTransport: FakeDriveHttpTransport
  private lateinit var driveManager: GoogleDriveBackupManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    File(context.filesDir, "pre_restore_safety").deleteRecursively()
    db = Room.inMemoryDatabaseBuilder(context, SkyPlusDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    repository = OfflineFinanceRepository(db, DatabaseInitializer(db), LoanEmiCalculator())
    preferencesDataStore = AppPreferencesDataStore(context)
    exportManager = FinancialExportManager(
      context = context,
      database = db,
      backupCryptoManager = BackupCryptoManager(),
      preferencesDataStore = preferencesDataStore,
    )
    fakeTransport = FakeDriveHttpTransport(accountEmail = "alice@gmail.com")
    driveManager = GoogleDriveBackupManager(
      context = context,
      database = db,
      exportManager = exportManager,
      preferencesDataStore = preferencesDataStore,
      firebaseAuthManager = null,
      httpTransport = fakeTransport,
    )
  }

  @After
  fun tearDown() {
    db.close()
    File(context.filesDir, "pre_restore_safety").deleteRecursively()
  }

  @Test
  fun `drive ownership verification rejects mismatched google account before touching files`() = runTest {
    repository.ensureInitialized()

    val res = driveManager.uploadEncryptedBackupToDrive(
      oauthAccessToken = "token-123",
      recoveryPassphrase = "Passphrase!2026".toCharArray(),
      uid = "uid-alice",
      expectedAccountEmail = "bob@gmail.com", // Fake Drive belongs to alice@gmail.com
      skipConnectivityCheck = true,
    )
    assertTrue(res.isFailure)
    assertTrue(res.exceptionOrNull()?.message.orEmpty().contains("does not match", ignoreCase = true))
    assertEquals(0, fakeTransport.storedFiles.size)
  }

  @Test
  fun `drive visible folder upload verifies encryption retention pruning and merge vs overwrite restore`() = runTest {
    repository.ensureInitialized()
    val today = 20_100L

    // Populate linked Loan + EMI + ShoppingList + ShoppingItem + Transaction
    val loanId = repository.addLoan(
      LoanEntity(
        loanName = "Solar Roof Loan",
        lender = "GreenBank",
        originalPrincipal = BigDecimal("200000.00"),
        outstandingPrincipal = BigDecimal("180000.00"),
        annualInterestRatePercent = BigDecimal("8.5"),
        emiAmount = BigDecimal("12000.00"),
        currencyCode = "INR",
        paymentDueDayOfMonth = 10,
        nextDueDateEpochDay = today + 15,
        remainingTenureMonths = 18,
        startDateEpochDay = today - 30,
        status = LoanStatus.ACTIVE,
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
      ),
    ).getOrThrow()

    db.loanDao().insertEmiPayment(
      EmiPaymentEntity(
        loanId = loanId,
        paymentDateEpochDay = today,
        totalPaidAmount = BigDecimal("12000.00"),
        principalComponent = BigDecimal("10000.00"),
        interestComponent = BigDecimal("2000.00"),
        extraPrepaymentAmount = BigDecimal.ZERO,
        remainingBalanceAfter = BigDecimal("180000.00"),
        currencyCode = "INR",
        note = "EMI #1",
        createdAtEpochMillis = 200L,
      ),
    )

    val listId = repository.upsertShoppingList(
      ShoppingListEntity(
        name = "Festival Provisions",
        budgetAmount = BigDecimal("6500.00"),
        currencyCode = "INR",
        notes = "Diwali shopping",
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
      ),
    ).getOrThrow()

    repository.upsertShoppingItem(
      ShoppingItemEntity(
        listId = listId,
        name = "Saffron Pack",
        categoryName = "Groceries",
        quantity = BigDecimal("2"),
        unit = "box",
        estimatedUnitPrice = BigDecimal("450.00"),
        actualUnitPrice = BigDecimal("440.00"),
        isPurchased = true,
        notes = "",
        createdAtEpochMillis = 120L,
        updatedAtEpochMillis = 120L,
      ),
    ).getOrThrow()

    repository.addTransaction(
      TransactionEntity(
        accountId = 0L,
        categoryId = 1L,
        type = TransactionType.EXPENSE,
        amount = BigDecimal("2450.00"),
        currencyCode = "INR",
        description = "Secret Sensitive Merchant XYZ",
        transactionDateEpochDay = today,
        deduplicationKey = "drive-test-tx-1",
        createdAtEpochMillis = 150L,
        updatedAtEpochMillis = 150L,
      ),
    ).getOrThrow()

    // Upload 8 sequential backups to verify Last-Known-Good retention (keeps baseline + latest 5 = 6)
    val pass = "DriveBackupPass!2026".toCharArray()
    repeat(8) { idx ->
      val msg = driveManager.uploadEncryptedBackupToDrive(
        oauthAccessToken = "token-alice",
        recoveryPassphrase = pass,
        uid = "uid-alice",
        expectedAccountEmail = "alice@gmail.com",
        skipConnectivityCheck = true,
      ).getOrThrow()
      assertTrue("Iteration $idx should mention folder name", msg.contains(GoogleDriveBackupManager.DRIVE_BACKUP_FOLDER_NAME))
    }

    // Folder must exist with exact name SKYPLUS FINANCE Backups
    assertTrue(fakeTransport.folders.values.any { it == GoogleDriveBackupManager.DRIVE_BACKUP_FOLDER_NAME })

    // Retention check: 1 earliest baseline + 5 latest = 6 total files kept
    val listed = driveManager.listBackupsInDrive(
      oauthAccessToken = "token-alice",
      uid = "uid-alice",
      expectedAccountEmail = "alice@gmail.com",
      skipConnectivityCheck = true,
    ).getOrThrow()
    assertEquals(6, listed.size)
    // Earliest baseline ("file-1") is preserved alongside latest 5 ("file-4".."file-8")
    assertEquals(fakeTransport.creationOrder.first(), listed.last().id)
    assertEquals(fakeTransport.creationOrder.last(), listed.first().id)

    // Verify encrypted payload contains zero plaintext secret strings
    val latestFileContent = fakeTransport.storedFiles.getValue(listed.first().id).content
    assertFalse(latestFileContent.contains("Secret Sensitive Merchant XYZ"))
    assertFalse(latestFileContent.contains("Solar Roof Loan"))
    assertFalse(latestFileContent.contains("Saffron Pack"))

    // Reset DB with DIFFERENT colliding local rows to verify Merge Restore FK remapping
    repository.eraseAllFinancialData().getOrThrow()
    repository.ensureInitialized()
    repository.addLoan(
      LoanEntity(
        loanName = "Local Existing Bike Loan",
        lender = "ICICI",
        originalPrincipal = BigDecimal("60000.00"),
        outstandingPrincipal = BigDecimal("50000.00"),
        annualInterestRatePercent = BigDecimal("10.0"),
        emiAmount = BigDecimal("5000.00"),
        currencyCode = "INR",
        paymentDueDayOfMonth = 15,
        nextDueDateEpochDay = today + 5,
        remainingTenureMonths = 10,
        startDateEpochDay = today - 20,
        status = LoanStatus.ACTIVE,
        createdAtEpochMillis = 300L,
        updatedAtEpochMillis = 300L,
      ),
    ).getOrThrow()

    // Merge restore must preserve BOTH existing bike loan AND restored Solar Roof Loan + its remapped EMI payment!
    val mergedCount = driveManager.restoreBackupFromDrive(
      oauthAccessToken = "token-alice",
      recoveryPassphrase = "DriveBackupPass!2026".toCharArray(),
      overwriteExisting = false,
      targetFileId = listed.first().id,
      uid = "uid-alice",
      expectedAccountEmail = "alice@gmail.com",
      skipConnectivityCheck = true,
    ).getOrThrow()
    assertTrue(mergedCount >= 4)

    val allLoansAfterMerge = db.loanDao().getAllLoansOnce()
    assertEquals(2, allLoansAfterMerge.size)
    val restoredSolarLoan = allLoansAfterMerge.first { it.loanName == "Solar Roof Loan" }
    val allEmis = db.loanDao().getAllEmiPaymentsOnce()
    assertEquals(1, allEmis.size)
    assertEquals(restoredSolarLoan.id, allEmis.first().loanId)

    val restoredList = db.shoppingDao().getAllShoppingListsOnce().first { it.name == "Festival Provisions" }
    val restoredItems = db.shoppingDao().getAllShoppingItemsOnce().filter { it.listId == restoredList.id }
    assertEquals(1, restoredItems.size)
    assertEquals("Saffron Pack", restoredItems.first().name)

    // Now test Overwrite restore -> creates pre-restore safety backup file and removes local Bike Loan
    driveManager.restoreBackupFromDrive(
      oauthAccessToken = "token-alice",
      recoveryPassphrase = "DriveBackupPass!2026".toCharArray(),
      overwriteExisting = true,
      targetFileId = listed.first().id,
      uid = "uid-alice",
      expectedAccountEmail = "alice@gmail.com",
      skipConnectivityCheck = true,
    ).getOrThrow()

    val loansAfterOverwrite = db.loanDao().getAllLoansOnce()
    assertEquals(1, loansAfterOverwrite.size)
    assertEquals("Solar Roof Loan", loansAfterOverwrite.first().loanName)
    assertTrue(
      "Overwrite restore should write encrypted pre-restore safety snapshot",
      File(context.filesDir, "pre_restore_safety").listFiles()?.isNotEmpty() == true,
    )
    assertEquals(1, repository.observeTransactions().first().size)
  }

  private class FakeDriveHttpTransport(
    private val accountEmail: String,
  ) : DriveHttpTransport {
    data class StoredDriveFile(
      val id: String,
      val name: String,
      val parentId: String,
      val createdMillis: Long,
      val content: String,
    )

    val folders = linkedMapOf<String, String>()
    val storedFiles = linkedMapOf<String, StoredDriveFile>()
    val creationOrder = mutableListOf<String>()
    private var seq = 0L

    override fun execute(
      method: String,
      url: String,
      headers: Map<String, String>,
      body: String?,
    ): DriveHttpResponse {
      return when {
        method == "GET" && url.contains("/drive/v3/about") -> {
          DriveHttpResponse(200, """{"user":{"emailAddress":"$accountEmail","displayName":"Alice"}}""")
        }

        method == "POST" && url.contains("uploadType=multipart") -> {
          val id = "file-${++seq}"
          val rawBody = body.orEmpty()
          val boundaryLine = rawBody.lineSequence().first()
          val parts = rawBody.split(boundaryLine).filter { it.contains("Content-Type:") }
          val metaPart = parts[0].substringAfter("\r\n\r\n").trim()
          val contentPart = parts[1].substringAfter("\r\n\r\n").trimEnd('\r', '\n')
          val metaJson = Json.parseToJsonElement(metaPart).jsonObject
          val name = metaJson["name"]!!.jsonPrimitive.content
          val parentId = metaJson["parents"]!!.toString().trim('[', ']', '"')
          storedFiles[id] = StoredDriveFile(
            id = id,
            name = name,
            parentId = parentId,
            createdMillis = 1_700_000_000_000L + seq * 1_000L,
            content = contentPart,
          )
          creationOrder += id
          DriveHttpResponse(200, """{"id":"$id","name":"$name","size":"${contentPart.length}"}""")
        }

        method == "POST" && url.contains("/drive/v3/files") -> {
          val metaJson = Json.parseToJsonElement(body.orEmpty()).jsonObject
          val name = metaJson["name"]!!.jsonPrimitive.content
          val folderId = "folder-${UUID.randomUUID().toString().take(6)}"
          folders[folderId] = name
          DriveHttpResponse(200, """{"id":"$folderId","name":"$name"}""")
        }

        method == "GET" && url.contains("alt=media") -> {
          val fileId = url.substringAfter("/files/").substringBefore("?")
          val file = storedFiles[fileId]
            ?: return DriveHttpResponse(404, "Not Found")
          DriveHttpResponse(200, file.content)
        }

        method == "GET" && url.contains("q=") && url.contains("vnd.google-apps.folder") -> {
          val decodedQ = URLDecoder.decode(url.substringAfter("q=").substringBefore("&"), "UTF-8")
          val targetName = decodedQ.substringAfter("name='").substringBefore("'")
          val matches = folders.entries.filter { it.value == targetName }
          val itemsJson = matches.joinToString(",") { """{"id":"${it.key}","name":"${it.value}"}""" }
          DriveHttpResponse(200, """{"files":[$itemsJson]}""")
        }

        method == "GET" && url.contains("q=") && url.contains("parents") -> {
          val asc = url.contains("createdTime%20asc") || url.contains("createdTime+asc") || url.contains("createdTime asc")
          val sorted = if (asc) {
            storedFiles.values.sortedBy { it.createdMillis }
          } else {
            storedFiles.values.sortedByDescending { it.createdMillis }
          }
          val itemsJson = sorted.joinToString(",") {
            """{"id":"${it.id}","name":"${it.name}","createdTime":"${it.createdMillis}","modifiedTime":"${it.createdMillis}","size":"${it.content.length}"}"""
          }
          DriveHttpResponse(200, """{"files":[$itemsJson]}""")
        }

        method == "GET" && url.contains("/drive/v3/files/") -> {
          val id = url.substringAfter("/files/").substringBefore("?")
          val folderName = folders[id]
          if (folderName != null) {
            DriveHttpResponse(200, """{"id":"$id","name":"$folderName","trashed":false}""")
          } else {
            DriveHttpResponse(404, "{}")
          }
        }

        method == "DELETE" && url.contains("/drive/v3/files/") -> {
          val id = url.substringAfter("/files/").substringBefore("?")
          storedFiles.remove(id)
          DriveHttpResponse(204, "")
        }

        else -> DriveHttpResponse(400, "Unexpected request: $method $url")
      }
    }
  }
}
