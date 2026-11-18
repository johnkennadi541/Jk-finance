package com.skyplus.finance.designsystem

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.math.BigDecimal

data class BarSeriesPoint(
  val label: String,
  val income: BigDecimal,
  val expense: BigDecimal,
)

data class TrendPoint(
  val label: String,
  val value: BigDecimal,
)

data class CategorySlice(
  val label: String,
  val amount: BigDecimal,
  val formattedAmount: String,
  val color: Color,
  val percentage: Float,
)

/**
 * Interactive Income vs Expenses grouped bar chart using SKYPLUS Royal Blue & Emerald Green.
 */
@Composable
fun SkyPlusIncomeExpenseBarChart(
  points: List<BarSeriesPoint>,
  modifier: Modifier = Modifier,
) {
  if (points.isEmpty()) return
  var selectedIndex by remember(points) { mutableIntStateOf(-1) }
  val maxVal = remember(points) {
    points.maxOfOrNull { maxOf(it.income, it.expense) }?.toFloat()?.coerceAtLeast(1f) ?: 1f
  }
  val animProgress by animateFloatAsState(
    targetValue = 1f,
    animationSpec = tween(500, easing = FastOutSlowInEasing),
    label = "bar_anim",
  )

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      LegendDot(color = SkyPlusBrandColors.EmeraldGreen, label = "Income")
      LegendDot(color = SkyPlusBrandColors.RoyalBlue, label = "Expenses")
    }

    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(150.dp),
    ) {
      val slotWidth = size.width / points.size.coerceAtLeast(1)
      val barWidth = (slotWidth * 0.28f).coerceIn(8.dp.toPx(), 24.dp.toPx())
      val gap = 4.dp.toPx()
      val bottomY = size.height - 6.dp.toPx()
      val usableHeight = bottomY - 8.dp.toPx()

      // Baseline
      drawLine(
        color = Color(0x339FB3C8),
        start = Offset(0f, bottomY),
        end = Offset(size.width, bottomY),
        strokeWidth = 1.dp.toPx(),
      )

      points.forEachIndexed { idx, point ->
        val centerX = slotWidth * idx + slotWidth / 2f
        val incHeight = ((point.income.toFloat() / maxVal) * usableHeight * animProgress).coerceAtLeast(2f)
        val expHeight = ((point.expense.toFloat() / maxVal) * usableHeight * animProgress).coerceAtLeast(2f)

        drawRoundRect(
          color = SkyPlusBrandColors.EmeraldGreen,
          topLeft = Offset(centerX - barWidth - gap / 2f, bottomY - incHeight),
          size = Size(barWidth, incHeight),
          cornerRadius = CornerRadius(6f, 6f),
        )
        drawRoundRect(
          color = SkyPlusBrandColors.RoyalBlue,
          topLeft = Offset(centerX + gap / 2f, bottomY - expHeight),
          size = Size(barWidth, expHeight),
          cornerRadius = CornerRadius(6f, 6f),
        )
      }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
      points.forEachIndexed { idx, p ->
        Text(
          text = p.label,
          style = MaterialTheme.typography.labelSmall,
          color = if (selectedIndex == idx) SkyPlusBrandColors.RoyalBlue else MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          modifier = Modifier.clickable { selectedIndex = if (selectedIndex == idx) -1 else idx },
        )
      }
    }
  }
}

/**
 * Smooth Net Worth / Spending Trend area line chart.
 */
@Composable
fun SkyPlusTrendLineChart(
  points: List<TrendPoint>,
  lineColor: Color = SkyPlusBrandColors.RoyalBlue,
  modifier: Modifier = Modifier,
) {
  if (points.size < 2) return
  val minVal = remember(points) { points.minOf { it.value.toFloat() } }
  val maxVal = remember(points) { points.maxOf { it.value.toFloat() } }
  val span = (maxVal - minVal).takeIf { it > 0.001f } ?: 1f

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(130.dp),
    ) {
      val padX = 10.dp.toPx()
      val padY = 12.dp.toPx()
      val usableW = size.width - padX * 2
      val usableH = size.height - padY * 2
      val stepX = usableW / (points.size - 1).coerceAtLeast(1)

      val coords = points.mapIndexed { i, p ->
        val x = padX + i * stepX
        val norm = (p.value.toFloat() - minVal) / span
        val y = padY + usableH * (1f - norm)
        Offset(x, y)
      }

      val linePath = Path().apply {
        moveTo(coords.first().x, coords.first().y)
        for (i in 1 until coords.size) {
          val prev = coords[i - 1]
          val curr = coords[i]
          val midX = (prev.x + curr.x) / 2f
          cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
        }
      }

      val fillPath = Path().apply {
        addPath(linePath)
        lineTo(coords.last().x, size.height)
        lineTo(coords.first().x, size.height)
        close()
      }

      drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(
          colors = listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0.02f)),
        ),
      )

      drawPath(
        path = linePath,
        color = lineColor,
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
      )

      coords.forEach { pt ->
        drawCircle(color = Color.White, radius = 4.5.dp.toPx(), center = pt)
        drawCircle(color = lineColor, radius = 3.dp.toPx(), center = pt)
      }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      val step = (points.size / 4).coerceAtLeast(1)
      points.forEachIndexed { idx, p ->
        if (idx == 0 || idx == points.lastIndex || idx % step == 0) {
          Text(
            text = p.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

/**
 * Category-wise Expense Donut Chart with interactive slice legend.
 */
@Composable
fun SkyPlusCategoryDonutChart(
  slices: List<CategorySlice>,
  centerTitle: String,
  centerValue: String,
  modifier: Modifier = Modifier,
) {
  if (slices.isEmpty()) return
  var selectedIdx by remember(slices) { mutableIntStateOf(0) }
  val activeSlice = slices.getOrNull(selectedIdx)

  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Box(
      modifier = Modifier.size(136.dp),
      contentAlignment = Alignment.Center,
    ) {
      Canvas(modifier = Modifier.size(136.dp)) {
        val strokeWidth = 22.dp.toPx()
        val inset = strokeWidth / 2f + 4.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        var startAngle = -90f
        slices.forEachIndexed { idx, slice ->
          val sweep = (slice.percentage / 100f) * 360f
          val padAngle = if (slices.size > 1) 2.5f else 0f
          drawArc(
            color = if (idx == selectedIdx) slice.color else slice.color.copy(alpha = 0.78f),
            startAngle = startAngle + padAngle / 2f,
            sweepAngle = (sweep - padAngle).coerceAtLeast(2f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = if (idx == selectedIdx) strokeWidth + 3.dp.toPx() else strokeWidth, cap = StrokeCap.Butt),
          )
          startAngle += sweep
        }
      }
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = activeSlice?.label ?: centerTitle,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = activeSlice?.let { "${it.percentage.toInt()}%" } ?: centerValue,
          style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleMedium),
          fontWeight = FontWeight.Bold,
        )
      }
    }

    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      slices.take(5).forEachIndexed { idx, slice ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
              if (idx == selectedIdx) slice.color.copy(alpha = 0.12f) else Color.Transparent,
            )
            .clickable { selectedIdx = idx }
            .padding(horizontal = 6.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
          ) {
            Box(
              modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(slice.color),
            )
            Text(
              text = slice.label,
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Text(
            text = slice.formattedAmount,
            style = SkyPlusFonts.numericStyle(MaterialTheme.typography.labelSmall),
            fontWeight = FontWeight.SemiBold,
          )
        }
      }
    }
  }
}

@Composable
private fun LegendDot(color: Color, label: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(
      modifier = Modifier
        .size(10.dp)
        .clip(CircleShape)
        .background(color),
    )
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/**
 * Circular animated Financial Health Score gauge (0..100).
 */
@Composable
fun SkyPlusHealthScoreGauge(
  score: Int?,
  accentColor: Color,
  centerSubtitle: String,
  modifier: Modifier = Modifier,
) {
  val targetFraction = if (score != null) (score.coerceIn(0, 100) / 100f) else 0f
  val animatedSweep by animateFloatAsState(
    targetValue = targetFraction * 270f,
    animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
    label = "health_gauge_sweep",
  )
  val trackColor = MaterialTheme.colorScheme.surfaceVariant

  Box(
    modifier = modifier.size(122.dp),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.size(122.dp)) {
      val stroke = 12.dp.toPx()
      val inset = stroke / 2f + 4.dp.toPx()
      val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
      drawArc(
        color = trackColor,
        startAngle = 135f,
        sweepAngle = 270f,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = arcSize,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
      )
      if (score != null) {
        drawArc(
          color = accentColor,
          startAngle = 135f,
          sweepAngle = animatedSweep.coerceAtLeast(4f),
          useCenter = false,
          topLeft = Offset(inset, inset),
          size = arcSize,
          style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
      }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = score?.toString() ?: "—",
        style = SkyPlusFonts.numericStyle(MaterialTheme.typography.headlineMedium),
        fontWeight = FontWeight.Bold,
        color = if (score != null) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = centerSubtitle,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
  }
}

data class ForecastChartPoint(
  val label: String,
  val projectedIncome: BigDecimal,
  val projectedExpenses: BigDecimal,
  val formattedIncome: String,
  val formattedExpenses: String,
  val formattedNet: String,
  val formattedEndingBalance: String,
  val isNetPositive: Boolean,
)

/**
 * Interactive 4-interval Forecast Cashflow chart (This Month EOM + Next 3 Months).
 * Tapping a column selects that month and displays exact breakdown metrics below.
 */
@Composable
fun SkyPlusForecastCashflowChart(
  points: List<ForecastChartPoint>,
  modifier: Modifier = Modifier,
) {
  if (points.isEmpty()) return
  var selectedIdx by remember(points) { mutableIntStateOf(0) }
  val active = points.getOrNull(selectedIdx)
  val maxVal = remember(points) {
    points.maxOfOrNull { maxOf(it.projectedIncome, it.projectedExpenses) }
      ?.toFloat()
      ?.coerceAtLeast(1f) ?: 1f
  }
  val animProgress by animateFloatAsState(
    targetValue = 1f,
    animationSpec = tween(500, easing = FastOutSlowInEasing),
    label = "forecast_bar_anim",
  )

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      LegendDot(color = SkyPlusBrandColors.EmeraldGreen, label = "Est. Income")
      LegendDot(color = SkyPlusBrandColors.RoyalBlue, label = "Est. Expenses")
    }

    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(144.dp),
    ) {
      val slotWidth = size.width / points.size.coerceAtLeast(1)
      val barWidth = (slotWidth * 0.26f).coerceIn(10.dp.toPx(), 24.dp.toPx())
      val gap = 4.dp.toPx()
      val bottomY = size.height - 6.dp.toPx()
      val usableHeight = bottomY - 8.dp.toPx()

      drawLine(
        color = Color(0x339FB3C8),
        start = Offset(0f, bottomY),
        end = Offset(size.width, bottomY),
        strokeWidth = 1.dp.toPx(),
      )

      points.forEachIndexed { idx, pt ->
        val centerX = slotWidth * idx + slotWidth / 2f
        val isSelected = idx == selectedIdx
        if (isSelected) {
          drawRoundRect(
            color = SkyPlusBrandColors.RoyalBlue.copy(alpha = 0.08f),
            topLeft = Offset(slotWidth * idx + 4.dp.toPx(), 2.dp.toPx()),
            size = Size(slotWidth - 8.dp.toPx(), bottomY - 2.dp.toPx()),
            cornerRadius = CornerRadius(10f, 10f),
          )
        }
        val incHeight = ((pt.projectedIncome.toFloat() / maxVal) * usableHeight * animProgress).coerceAtLeast(2f)
        val expHeight = ((pt.projectedExpenses.toFloat() / maxVal) * usableHeight * animProgress).coerceAtLeast(2f)

        drawRoundRect(
          color = if (isSelected) SkyPlusBrandColors.EmeraldGreen else SkyPlusBrandColors.EmeraldGreen.copy(alpha = 0.72f),
          topLeft = Offset(centerX - barWidth - gap / 2f, bottomY - incHeight),
          size = Size(barWidth, incHeight),
          cornerRadius = CornerRadius(6f, 6f),
        )
        drawRoundRect(
          color = if (isSelected) SkyPlusBrandColors.RoyalBlue else SkyPlusBrandColors.RoyalBlue.copy(alpha = 0.72f),
          topLeft = Offset(centerX + gap / 2f, bottomY - expHeight),
          size = Size(barWidth, expHeight),
          cornerRadius = CornerRadius(6f, 6f),
        )
      }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
      points.forEachIndexed { idx, pt ->
        val selected = idx == selectedIdx
        Box(
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SkyPlusBrandColors.RoyalBlue.copy(alpha = 0.14f) else Color.Transparent)
            .clickable { selectedIdx = idx }
            .testTag("forecast_point_$idx")
            .padding(horizontal = 10.dp, vertical = 6.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = pt.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) SkyPlusBrandColors.RoyalBlue else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
          )
        }
      }
    }

    if (active != null) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
          .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("${active.label} Est. Net", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = active.formattedNet,
            style = SkyPlusFonts.numericStyle(MaterialTheme.typography.bodyMedium),
            color = if (active.isNetPositive) SkyPlusBrandColors.EmeraldGreen else SkyPlusBrandColors.ErrorCoral,
            fontWeight = FontWeight.Bold,
          )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
          Text("Est. End Balance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = active.formattedEndingBalance,
            style = SkyPlusFonts.numericStyle(MaterialTheme.typography.bodyMedium),
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }
  }
}

/**
 * Visual proportional bar comparing Weekday vs Weekend spending share.
 */
@Composable
fun SkyPlusWeekdayWeekendSplitBar(
  weekdaySharePercent: Float,
  weekendSharePercent: Float,
  modifier: Modifier = Modifier,
) {
  val total = (weekdaySharePercent + weekendSharePercent).coerceAtLeast(1f)
  val weekdayRatio = (weekdaySharePercent / total).coerceIn(0.02f, 0.98f)
  Row(
    modifier = modifier
      .fillMaxWidth()
      .height(12.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(SkyPlusBrandColors.SoftCyan.copy(alpha = 0.25f)),
  ) {
    Box(
      modifier = Modifier
        .weight(weekdayRatio)
        .height(12.dp)
        .background(SkyPlusBrandColors.RoyalBlue),
    )
    Box(
      modifier = Modifier
        .weight((1f - weekdayRatio).coerceAtLeast(0.02f))
        .height(12.dp)
        .background(SkyPlusBrandColors.EmeraldGreen),
    )
  }
}

