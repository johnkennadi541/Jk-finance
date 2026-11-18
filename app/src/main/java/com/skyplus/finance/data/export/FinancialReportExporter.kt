package com.skyplus.finance.data.export

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.skyplus.finance.data.security.AppPreferencesDataStore
import com.skyplus.finance.domain.report.FinancialReportData
import com.skyplus.finance.domain.report.ReportSection
import com.skyplus.finance.util.CurrencyFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FinancialReportExporter @Inject constructor(
  @ApplicationContext private val context: Context,
  private val preferencesDataStore: AppPreferencesDataStore,
) {

  private fun exportDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }

  suspend fun exportPdf(report: FinancialReportData): Result<File> = runCatching {
    val fileSlug = report.periodLabel.lowercase().replace(" ", "_")
    val outFile = File(exportDir(), "skyplus_${fileSlug}_report_${report.generatedDateText}.pdf")
    val lines = flattenReportLines(report)

    val nativeSuccess = runCatching {
      writeNativeMultiPagePdf(outFile, report, lines)
    }.isSuccess

    if (!nativeSuccess) {
      writeFallbackMultiPagePdf14(outFile, report, lines)
    }

    preferencesDataStore.recordLocalExportTimestamp(System.currentTimeMillis())
    outFile
  }

  suspend fun exportDocx(report: FinancialReportData): Result<File> = runCatching {
    val fileSlug = report.periodLabel.lowercase().replace(" ", "_")
    val outFile = File(exportDir(), "skyplus_${fileSlug}_report_${report.generatedDateText}.docx")

    ZipOutputStream(FileOutputStream(outFile)).use { zip ->
      writeZipEntry(zip, "[Content_Types].xml", contentTypesXml())
      writeZipEntry(zip, "_rels/.rels", rootRelsXml())
      writeZipEntry(zip, "word/_rels/document.xml.rels", documentRelsXml())
      writeZipEntry(zip, "word/styles.xml", wordStylesXml())
      writeZipEntry(zip, "word/document.xml", buildWordDocumentXml(report))
    }

    preferencesDataStore.recordLocalExportTimestamp(System.currentTimeMillis())
    outFile
  }

  private fun writeNativeMultiPagePdf(
    outFile: File,
    report: FinancialReportData,
    lines: List<PdfLine>,
  ) {
    val pageWidth = 595
    val pageHeight = 842
    val marginX = 40f
    val maxY = 785f

    val document = PdfDocument()
    val headerBgPaint = Paint().apply { color = Color.rgb(10, 46, 92) } // SKYPLUS Deep Ocean Blue
    val headerGoldAccentPaint = Paint().apply { color = Color.rgb(212, 175, 55) } // SKYPLUS Gold
    val headerTitlePaint = Paint().apply {
      color = Color.WHITE
      textSize = 17f
      isFakeBoldText = true
    }
    val headerSubPaint = Paint().apply {
      color = Color.rgb(220, 235, 250)
      textSize = 10f
    }
    val sectionPaint = Paint().apply {
      color = Color.rgb(0, 150, 131) // SKYPLUS Teal
      textSize = 12.5f
      isFakeBoldText = true
    }
    val tableHeaderPaint = Paint().apply {
      color = Color.rgb(7, 27, 53) // Midnight Navy
      textSize = 10f
      isFakeBoldText = true
    }
    val bodyPaint = Paint().apply {
      color = Color.rgb(16, 42, 67)
      textSize = 10f
    }
    val footerPaint = Paint().apply {
      color = Color.rgb(100, 116, 139)
      textSize = 9f
    }

    // Chunk lines across pages cleanly
    val pagesContent = mutableListOf<List<PdfLine>>()
    var currentChunk = mutableListOf<PdfLine>()
    var simY = 102f
    for (line in lines) {
      val step = when (line.style) {
        PdfLineStyle.SECTION -> 22f
        PdfLineStyle.TABLE_HEADER -> 17f
        PdfLineStyle.BODY -> 15f
        PdfLineStyle.SPACER -> 8f
      }
      if (simY + step > maxY && currentChunk.isNotEmpty()) {
        pagesContent.add(currentChunk)
        currentChunk = mutableListOf()
        simY = 102f
      }
      currentChunk.add(line)
      simY += step
    }
    if (currentChunk.isNotEmpty() || pagesContent.isEmpty()) {
      pagesContent.add(currentChunk)
    }

    val totalPages = pagesContent.size
    pagesContent.forEachIndexed { pageIdx, chunk ->
      val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIdx + 1).create()
      val page = document.startPage(pageInfo)
      val canvas = page.canvas

      // Brand header band with Deep Ocean Blue & Gold bottom rule
      canvas.drawRect(0f, 0f, pageWidth.toFloat(), 74f, headerBgPaint)
      canvas.drawRect(0f, 71f, pageWidth.toFloat(), 74f, headerGoldAccentPaint)
      canvas.drawText(report.title, marginX, 32f, headerTitlePaint)
      canvas.drawText(
        "Period: ${report.dateRangeText}  |  Generated: ${report.generatedDateText}  |  Display: ${report.displayCurrencyCode}",
        marginX,
        54f,
        headerSubPaint,
      )

      var y = 102f
      for (line in chunk) {
        when (line.style) {
          PdfLineStyle.SECTION -> {
            canvas.drawText(line.text, marginX, y, sectionPaint)
            y += 22f
          }

          PdfLineStyle.TABLE_HEADER -> {
            canvas.drawText(line.text, marginX, y, tableHeaderPaint)
            y += 17f
          }

          PdfLineStyle.BODY -> {
            canvas.drawText(line.text, marginX, y, bodyPaint)
            y += 15f
          }

          PdfLineStyle.SPACER -> y += 8f
        }
      }

      canvas.drawText(
        "SKYPLUS FINANCE  •  Page ${pageIdx + 1} of $totalPages",
        marginX,
        816f,
        footerPaint,
      )
      document.finishPage(page)
    }

    FileOutputStream(outFile).use { fos -> document.writeTo(fos) }
    document.close()
  }

  private fun writeFallbackMultiPagePdf14(
    outFile: File,
    report: FinancialReportData,
    lines: List<PdfLine>,
  ) {
    val linesPerPage = 44
    val allTextLines = buildList {
      add(report.title)
      add("Period: ${report.dateRangeText} | Generated: ${report.generatedDateText} | Display: ${report.displayCurrencyCode}")
      add("")
      lines.forEach { if (it.style != PdfLineStyle.SPACER) add(it.text) else add("") }
    }
    val chunks = allTextLines.chunked(linesPerPage).ifEmpty { listOf(listOf(report.title)) }
    val pageCount = chunks.size

    // Object IDs: 1=Catalog, 2=Pages, 3=Font, then per page (pageObj, contentObj)
    val pageObjIds = (0 until pageCount).map { 4 + it * 2 }
    val objects = mutableListOf<String>()
    objects.add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
    objects.add("2 0 obj\n<< /Type /Pages /Kids [${pageObjIds.joinToString(" ") { "$it 0 R" }}] /Count $pageCount >>\nendobj\n")
    objects.add("3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")

    chunks.forEachIndexed { idx, pageLines ->
      val pageId = 4 + idx * 2
      val contentId = pageId + 1
      val stream = buildString {
        append("BT\n/F1 10.5 Tf\n40 792 Td\n15 TL\n")
        pageLines.forEach { raw ->
          val safe = raw.map { ch -> if (ch.code in 32..126) ch else '?' }
            .joinToString("")
            .replace("\\", "\\\\")
            .replace("(", "\\(")
            .replace(")", "\\)")
          append("($safe) Tj T*\n")
        }
        append("(SKYPLUS FINANCE - Page ${idx + 1} of $pageCount) Tj\nET\n")
      }
      val streamBytes = stream.toByteArray(Charsets.US_ASCII)
      objects.add("$pageId 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents $contentId 0 R /Resources << /Font << /F1 3 0 R >> >> >>\nendobj\n")
      objects.add("$contentId 0 obj\n<< /Length ${streamBytes.size} >>\nstream\n${stream}endstream\nendobj\n")
    }

    val pdf = StringBuilder("%PDF-1.4\n")
    val offsets = mutableListOf<Int>()
    for (obj in objects) {
      offsets.add(pdf.toString().toByteArray(Charsets.US_ASCII).size)
      pdf.append(obj)
    }
    val xrefOffset = pdf.toString().toByteArray(Charsets.US_ASCII).size
    pdf.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
    for (offset in offsets) {
      pdf.append(String.format(Locale.US, "%010d 00000 n \n", offset))
    }
    pdf.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
    outFile.writeBytes(pdf.toString().toByteArray(Charsets.US_ASCII))
  }

  private fun flattenReportLines(report: FinancialReportData): List<PdfLine> = buildList {
    if (ReportSection.SUMMARY in report.includedSections) {
      add(PdfLine("1. Executive Financial Summary", PdfLineStyle.SECTION))
      add(PdfLine("Opening Balance: ${report.openingBalanceFormatted}   |   Closing Balance: ${report.closingBalanceFormatted}", PdfLineStyle.BODY))
      add(PdfLine("Total Income: ${report.totalIncomeFormatted}   |   Total Expenses: ${report.totalExpensesFormatted}", PdfLineStyle.BODY))
      add(PdfLine("Net Savings: ${report.netSavingsFormatted}   |   Savings Allocated: ${report.savingsAllocatedFormatted}", PdfLineStyle.BODY))
      if (report.unconvertedForeignTxCount > 0) {
        add(PdfLine("Note: ${report.unconvertedForeignTxCount} multi-currency entry(s) without an exchange rate are shown in Multi-Currency Holdings.", PdfLineStyle.BODY))
      }
      add(PdfLine("", PdfLineStyle.SPACER))
    }

    if (ReportSection.NET_WORTH_CHANGE in report.includedSections) {
      add(PdfLine("2. Net Worth Changes", PdfLineStyle.SECTION))
      add(PdfLine("Opening Net Worth: ${report.netWorthStartFormatted}   |   Closing Net Worth: ${report.netWorthEndFormatted}", PdfLineStyle.BODY))
      add(PdfLine("Net Worth Change in Period: ${report.netWorthChangeFormatted}", PdfLineStyle.BODY))
      add(PdfLine("", PdfLineStyle.SPACER))
    }

    if (ReportSection.FINANCIAL_HEALTH in report.includedSections) {
      add(PdfLine("3. Financial Health Trends", PdfLineStyle.SECTION))
      val scoreText = report.healthScore?.let { "$it / 100 (${report.healthBandLabel})" } ?: report.healthBandLabel
      add(PdfLine("Health Score: $scoreText   |   Savings Rate: ${report.healthSavingsRatePercent}", PdfLineStyle.BODY))
      add(PdfLine("Debt-to-Income: ${report.healthDebtToIncomePercent}   |   Emergency Runway: ${report.healthEmergencyMonths}", PdfLineStyle.BODY))
      if (report.healthTrendSummary.isNotBlank()) {
        add(PdfLine(report.healthTrendSummary, PdfLineStyle.BODY))
      }
      add(PdfLine("", PdfLineStyle.SPACER))
    }

    if (ReportSection.CATEGORY_EXPENSES in report.includedSections) {
      add(PdfLine("4. Category-wise Expense Breakdown", PdfLineStyle.SECTION))
      if (report.categoryExpenses.isEmpty()) {
        add(PdfLine("No expenses recorded in this period.", PdfLineStyle.BODY))
      } else {
        add(PdfLine("Category | Amount | Share", PdfLineStyle.TABLE_HEADER))
        report.categoryExpenses.forEach { row ->
          add(PdfLine("${row.name} | ${row.formattedAmount} | ${row.sharePercent}%", PdfLineStyle.BODY))
        }
      }
      add(PdfLine("", PdfLineStyle.SPACER))
    }

    if (ReportSection.INCOME_SOURCES in report.includedSections) {
      add(PdfLine("5. Income Source Breakdown", PdfLineStyle.SECTION))
      if (report.incomeSources.isEmpty()) {
        add(PdfLine("No income recorded in this period.", PdfLineStyle.BODY))
      } else {
        add(PdfLine("Source | Amount | Share", PdfLineStyle.TABLE_HEADER))
        report.incomeSources.forEach { row ->
          add(PdfLine("${row.name} | ${row.formattedAmount} | ${row.sharePercent}%", PdfLineStyle.BODY))
        }
      }
      add(PdfLine("", PdfLineStyle.SPACER))
    }

    if (ReportSection.BUDGET_VS_ACTUAL in report.includedSections) {
      add(PdfLine("6. Budget vs Actual Spending", PdfLineStyle.SECTION))
      if (report.budgetRows.isEmpty()) {
        add(PdfLine("No budgets configured.", PdfLineStyle.BODY))
      } else {
        add(PdfLine("Category (Month) | Limit | Spent | Remaining | Status", PdfLineStyle.TABLE_HEADER))
        report.budgetRows.forEach { b ->
          add(PdfLine("${b.categoryName} (${b.yearMonth}) | ${b.limitFormatted} | ${b.spentFormatted} | ${b.remainingFormatted} | ${b.status.name}", PdfLineStyle.BODY))
        }
      }
      add(PdfLine("", PdfLineStyle.SPACER))
    }

    if (ReportSection.LOANS_AND_EMI in report.includedSections) {
      add(PdfLine("7. Loans & EMI Payments (Period Paid: ${report.totalEmiPaidInPeriodFormatted})", PdfLineStyle.SECTION))
      if (report.loanRows.isEmpty()) {
        add(PdfLine("No loans recorded.", PdfLineStyle.BODY))
      } else {
        add(PdfLine("Loan (Lender) | Outstanding | Monthly EMI | APR | Next Due", PdfLineStyle.TABLE_HEADER))
        report.loanRows.forEach { l ->
          add(PdfLine("${l.loanName} (${l.lender}) | ${l.outstandingFormatted} | ${l.emiFormatted} | ${l.annualRatePercent} | ${l.nextDueDate}", PdfLineStyle.BODY))
        }
      }
      if (report.emiPaymentRows.isNotEmpty()) {
        add(PdfLine("EMI Payments Recorded in Period:", PdfLineStyle.TABLE_HEADER))
        report.emiPaymentRows.forEach { e ->
          add(PdfLine("${e.date} | ${e.loanName} | Total: ${e.totalPaidFormatted} | Prin: ${e.principalFormatted} | Int: ${e.interestFormatted}", PdfLineStyle.BODY))
        }
      }
      add(PdfLine("", PdfLineStyle.SPACER))
    }

    if (ReportSection.SAVINGS_GOALS in report.includedSections) {
      add(PdfLine("8. Savings Goal Progress", PdfLineStyle.SECTION))
      if (report.savingsGoalRows.isEmpty()) {
        add(PdfLine("No savings goals configured.", PdfLineStyle.BODY))
      } else {
        add(PdfLine("Goal | Saved | Target | Progress | Target Date", PdfLineStyle.TABLE_HEADER))
        report.savingsGoalRows.forEach { g ->
          add(PdfLine("${g.goalName} | ${g.savedFormatted} | ${g.targetFormatted} | ${g.progressPercent}% | ${g.targetDate}", PdfLineStyle.BODY))
        }
      }
      add(PdfLine("", PdfLineStyle.SPACER))
    }

    if (ReportSection.MULTI_CURRENCY in report.includedSections && report.currencyHoldings.isNotEmpty()) {
      add(PdfLine("9. Multi-Currency Holdings", PdfLineStyle.SECTION))
      add(PdfLine("Currency | Income | Expenses | Savings | Net", PdfLineStyle.TABLE_HEADER))
      report.currencyHoldings.forEach { c ->
        add(
          PdfLine(
            "${c.currencyCode} | ${CurrencyFormatter.format(c.incomeTotal, c.currencyCode)} | ${CurrencyFormatter.format(c.expenseTotal, c.currencyCode)} | ${CurrencyFormatter.format(c.savingsTotal, c.currencyCode)} | ${CurrencyFormatter.format(c.netBalance, c.currencyCode)}",
            PdfLineStyle.BODY,
          ),
        )
      }
      add(PdfLine("", PdfLineStyle.SPACER))
    }

    if (ReportSection.TRANSACTION_HISTORY in report.includedSections) {
      add(PdfLine("10. Detailed Transaction History", PdfLineStyle.SECTION))
      if (report.transactionRows.isEmpty()) {
        add(PdfLine("No transactions recorded in this date range.", PdfLineStyle.BODY))
      } else {
        add(PdfLine("Date | Type | Category | Description | Amount", PdfLineStyle.TABLE_HEADER))
        report.transactionRows.forEach { tx ->
          val convSuffix = tx.convertedDisplayFormatted?.let { " (~$it)" } ?: ""
          add(PdfLine("${tx.date} | ${tx.type.name} | ${tx.categoryName} | ${tx.description.take(28)} | ${tx.originalFormatted}$convSuffix", PdfLineStyle.BODY))
        }
      }
    }
  }

  private fun buildWordDocumentXml(report: FinancialReportData): String {
    val body = StringBuilder()

    body.append(wordHeadingParagraph(report.title, "0A2E5C", 32))
    body.append(wordParagraph("SKYPLUS CORPORATE WEALTH REPORT  |  Period: ${report.dateRangeText}  |  Generated: ${report.generatedDateText}  |  Display Currency: ${report.displayCurrencyCode}", "486581", 18))

    if (ReportSection.SUMMARY in report.includedSections) {
      body.append(wordHeadingParagraph("1. Executive Financial Summary", "009683", 24))
      body.append(
        wordTable(
          headers = listOf("Metric", "Value"),
          rows = listOf(
            listOf("Opening Balance", report.openingBalanceFormatted),
            listOf("Total Income", report.totalIncomeFormatted),
            listOf("Total Expenses", report.totalExpensesFormatted),
            listOf("Net Savings", report.netSavingsFormatted),
            listOf("Savings Allocated", report.savingsAllocatedFormatted),
            listOf("Closing Balance", report.closingBalanceFormatted),
          ),
        ),
      )
    }

    if (ReportSection.NET_WORTH_CHANGE in report.includedSections) {
      body.append(wordHeadingParagraph("2. Net Worth Changes", "D4AF37", 24))
      body.append(
        wordTable(
          headers = listOf("Net Worth Metric", "Value"),
          rows = listOf(
            listOf("Start of Period Net Worth", report.netWorthStartFormatted),
            listOf("End of Period Net Worth", report.netWorthEndFormatted),
            listOf("Net Worth Change", report.netWorthChangeFormatted),
          ),
        ),
      )
    }

    if (ReportSection.FINANCIAL_HEALTH in report.includedSections) {
      body.append(wordHeadingParagraph("3. Financial Health Trends", "009683", 24))
      val scoreText = report.healthScore?.let { "$it / 100 (${report.healthBandLabel})" } ?: report.healthBandLabel
      body.append(
        wordTable(
          headers = listOf("Health Indicator", "Measurement"),
          rows = listOf(
            listOf("Overall Financial Health Score", scoreText),
            listOf("Savings Rate", report.healthSavingsRatePercent),
            listOf("Debt-to-Income Ratio", report.healthDebtToIncomePercent),
            listOf("Emergency Reserve Runway", report.healthEmergencyMonths),
          ),
        ),
      )
      if (report.healthTrendSummary.isNotBlank()) {
        body.append(wordParagraph(report.healthTrendSummary))
      }
    }

    if (ReportSection.CATEGORY_EXPENSES in report.includedSections) {
      body.append(wordHeadingParagraph("2. Category-wise Expense Breakdown", "2878D0", 24))
      if (report.categoryExpenses.isEmpty()) {
        body.append(wordParagraph("No expenses recorded in this period."))
      } else {
        body.append(
          wordTable(
            headers = listOf("Category", "Amount", "Share"),
            rows = report.categoryExpenses.map { listOf(it.name, it.formattedAmount, "${it.sharePercent}%") },
          ),
        )
      }
    }

    if (ReportSection.INCOME_SOURCES in report.includedSections) {
      body.append(wordHeadingParagraph("3. Income Source Breakdown", "2878D0", 24))
      if (report.incomeSources.isEmpty()) {
        body.append(wordParagraph("No income recorded in this period."))
      } else {
        body.append(
          wordTable(
            headers = listOf("Income Source", "Amount", "Share"),
            rows = report.incomeSources.map { listOf(it.name, it.formattedAmount, "${it.sharePercent}%") },
          ),
        )
      }
    }

    if (ReportSection.BUDGET_VS_ACTUAL in report.includedSections) {
      body.append(wordHeadingParagraph("4. Budget vs Actual Spending", "2878D0", 24))
      if (report.budgetRows.isEmpty()) {
        body.append(wordParagraph("No budgets configured."))
      } else {
        body.append(
          wordTable(
            headers = listOf("Category", "Month", "Limit", "Spent", "Remaining", "Status"),
            rows = report.budgetRows.map {
              listOf(it.categoryName, it.yearMonth, it.limitFormatted, it.spentFormatted, it.remainingFormatted, it.status.name)
            },
          ),
        )
      }
    }

    if (ReportSection.LOANS_AND_EMI in report.includedSections) {
      body.append(wordHeadingParagraph("7. Loans & EMI Payments (Period Paid: ${report.totalEmiPaidInPeriodFormatted})", "0A2E5C", 24))
      if (report.loanRows.isEmpty()) {
        body.append(wordParagraph("No loans recorded."))
      } else {
        body.append(
          wordTable(
            headers = listOf("Loan", "Lender", "Outstanding", "EMI", "APR", "Next Due"),
            rows = report.loanRows.map {
              listOf(it.loanName, it.lender, it.outstandingFormatted, it.emiFormatted, it.annualRatePercent, it.nextDueDate)
            },
          ),
        )
      }
      if (report.emiPaymentRows.isNotEmpty()) {
        body.append(
          wordTable(
            headers = listOf("Date", "Loan", "Paid", "Principal", "Interest", "Extra"),
            rows = report.emiPaymentRows.map {
              listOf(it.date, it.loanName, it.totalPaidFormatted, it.principalFormatted, it.interestFormatted, it.extraPrepaymentFormatted)
            },
          ),
        )
      }
    }

    if (ReportSection.SAVINGS_GOALS in report.includedSections) {
      body.append(wordHeadingParagraph("6. Savings Goal Progress", "00BFA6", 24))
      if (report.savingsGoalRows.isEmpty()) {
        body.append(wordParagraph("No savings goals configured."))
      } else {
        body.append(
          wordTable(
            headers = listOf("Goal", "Category", "Saved", "Target", "Progress", "Target Date"),
            rows = report.savingsGoalRows.map {
              listOf(it.goalName, it.category, it.savedFormatted, it.targetFormatted, "${it.progressPercent}%", it.targetDate)
            },
          ),
        )
      }
    }

    if (ReportSection.MULTI_CURRENCY in report.includedSections && report.currencyHoldings.isNotEmpty()) {
      body.append(wordHeadingParagraph("7. Multi-Currency Holdings", "2878D0", 24))
      body.append(
        wordTable(
          headers = listOf("Currency", "Income", "Expenses", "Savings", "Net Balance"),
          rows = report.currencyHoldings.map {
            listOf(
              it.currencyCode,
              CurrencyFormatter.format(it.incomeTotal, it.currencyCode),
              CurrencyFormatter.format(it.expenseTotal, it.currencyCode),
              CurrencyFormatter.format(it.savingsTotal, it.currencyCode),
              CurrencyFormatter.format(it.netBalance, it.currencyCode),
            )
          },
        ),
      )
    }

    if (ReportSection.TRANSACTION_HISTORY in report.includedSections) {
      body.append(wordHeadingParagraph("8. Detailed Transaction History", "2878D0", 24))
      if (report.transactionRows.isEmpty()) {
        body.append(wordParagraph("No transactions recorded in this date range."))
      } else {
        body.append(
          wordTable(
            headers = listOf("Date", "Type", "Category", "Description", "Amount", "Converted"),
            rows = report.transactionRows.map {
              listOf(
                it.date,
                it.type.name,
                it.categoryName,
                it.description,
                it.originalFormatted,
                it.convertedDisplayFormatted ?: "—",
              )
            },
          ),
        )
      }
    }

    return """
      <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
        <w:body>
          $body
          <w:sectPr>
            <w:pgSz w:w="11906" w:h="16838"/>
            <w:pgMar w:top="1080" w:right="1080" w:bottom="1080" w:left="1080"/>
          </w:sectPr>
        </w:body>
      </w:document>
    """.trimIndent()
  }

  private fun wordHeadingParagraph(text: String, hexColor: String, halfPoints: Int): String {
    val safe = escapeXml(text)
    return """
      <w:p>
        <w:pPr><w:spacing w:before="200" w:after="100"/><w:keepNext/></w:pPr>
        <w:r>
          <w:rPr><w:b/><w:color w:val="$hexColor"/><w:sz w:val="$halfPoints"/></w:rPr>
          <w:t>$safe</w:t>
        </w:r>
      </w:p>
    """.trimIndent()
  }

  private fun wordParagraph(text: String, hexColor: String = "071B35", halfPoints: Int = 20): String {
    val safe = escapeXml(text)
    return """
      <w:p>
        <w:pPr><w:spacing w:after="100"/></w:pPr>
        <w:r>
          <w:rPr><w:color w:val="$hexColor"/><w:sz w:val="$halfPoints"/></w:rPr>
          <w:t>$safe</w:t>
        </w:r>
      </w:p>
    """.trimIndent()
  }

  private fun wordTable(headers: List<String>, rows: List<List<String>>): String {
    val sb = StringBuilder()
    sb.append(
      """
      <w:tbl>
        <w:tblPr>
          <w:tblW w:w="5000" w:type="pct"/>
          <w:tblBorders>
            <w:top w:val="single" w:sz="4" w:space="0" w:color="CBD9EA"/>
            <w:left w:val="single" w:sz="4" w:space="0" w:color="CBD9EA"/>
            <w:bottom w:val="single" w:sz="4" w:space="0" w:color="CBD9EA"/>
            <w:right w:val="single" w:sz="4" w:space="0" w:color="CBD9EA"/>
            <w:insideH w:val="single" w:sz="4" w:space="0" w:color="E6EEF8"/>
            <w:insideV w:val="single" w:sz="4" w:space="0" w:color="E6EEF8"/>
          </w:tblBorders>
        </w:tblPr>
      """.trimIndent(),
    )
    sb.append("<w:tr><w:trPr><w:tblHeader/></w:trPr>")
    headers.forEach { h ->
      sb.append(
        """
        <w:tc>
          <w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="2878D0"/></w:tcPr>
          <w:p><w:r><w:rPr><w:b/><w:color w:val="FFFFFF"/><w:sz w:val="18"/></w:rPr><w:t>${escapeXml(h)}</w:t></w:r></w:p>
        </w:tc>
        """.trimIndent(),
      )
    }
    sb.append("</w:tr>")

    rows.forEachIndexed { idx, row ->
      val fill = if (idx % 2 == 1) "F3F7FC" else "FFFFFF"
      sb.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
      row.forEach { cell ->
        sb.append(
          """
          <w:tc>
            <w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="$fill"/></w:tcPr>
            <w:p><w:r><w:rPr><w:color w:val="071B35"/><w:sz w:val="18"/></w:rPr><w:t>${escapeXml(cell)}</w:t></w:r></w:p>
          </w:tc>
          """.trimIndent(),
        )
      }
      sb.append("</w:tr>")
    }
    sb.append("</w:tbl>")
    return sb.toString()
  }

  private fun writeZipEntry(zip: ZipOutputStream, name: String, content: String) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(content.toByteArray(Charsets.UTF_8))
    zip.closeEntry()
  }

  private fun escapeXml(input: String): String =
    input
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")

  private fun contentTypesXml(): String = """
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
      <Default Extension="xml" ContentType="application/xml"/>
      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
      <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
    </Types>
  """.trimIndent()

  private fun rootRelsXml(): String = """
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
    </Relationships>
  """.trimIndent()

  private fun documentRelsXml(): String = """
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
    </Relationships>
  """.trimIndent()

  private fun wordStylesXml(): String = """
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
      <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
        <w:name w:val="Normal"/>
        <w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:sz w:val="20"/></w:rPr>
      </w:style>
    </w:styles>
  """.trimIndent()

  private enum class PdfLineStyle { SECTION, TABLE_HEADER, BODY, SPACER }
  private data class PdfLine(val text: String, val style: PdfLineStyle)
}
