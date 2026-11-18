package com.skyplus.finance.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Reusable SKYPLUS UI Components ensuring uniform corner radii, spacing, contrast, and symmetry.
 */
@Composable
fun SkyPlusPortfolioCard(
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(16.dp),
  content: @Composable ColumnScope.() -> Unit,
) {
  val spacing = LocalSkyPlusSpacing.current
  val cardModifier = if (onClick != null) {
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(spacing.cardRadius))
      .clickable(onClick = onClick)
  } else {
    modifier.fillMaxWidth()
  }

  Card(
    modifier = cardModifier,
    shape = RoundedCornerShape(spacing.cardRadius),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(contentPadding),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      content = content,
    )
  }
}

@Composable
fun SkyPlusHeroCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val spacing = LocalSkyPlusSpacing.current
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(spacing.heroRadius),
    border = BorderStroke(1.dp, SkyPlusGradients.GoldAccentBorder),
    elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(SkyPlusGradients.HeroOceanGradient)
        .padding(spacing.heroPadding),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      content = content,
    )
  }
}

@Composable
fun SkyPlusGoldBadge(
  text: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = SkyPlusBrandColors.GoldAccent.copy(alpha = 0.16f),
    border = BorderStroke(1.dp, SkyPlusBrandColors.GoldAccent.copy(alpha = 0.45f)),
    shape = RoundedCornerShape(10.dp),
    modifier = modifier,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = SkyPlusBrandColors.GoldSoft,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}

@Composable
fun SkyPlusExpandableCard(
  title: String,
  subtitle: String? = null,
  trailingBadge: String? = null,
  trailingBadgeColor: Color = SkyPlusBrandColors.EmeraldGreen,
  initiallyExpanded: Boolean = false,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  var expanded by remember { mutableStateOf(initiallyExpanded) }
  SkyPlusPortfolioCard(modifier = modifier) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .minimumInteractiveComponentSize()
        .clickable { expanded = !expanded },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (!subtitle.isNullOrBlank()) {
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (!trailingBadge.isNullOrBlank()) {
        Surface(
          color = trailingBadgeColor.copy(alpha = 0.14f),
          shape = RoundedCornerShape(10.dp),
        ) {
          Text(
            text = trailingBadge,
            style = MaterialTheme.typography.labelSmall,
            color = trailingBadgeColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
        Spacer(modifier = Modifier.width(6.dp))
      }
      Icon(
        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
        contentDescription = if (expanded) "Collapse" else "Expand",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    AnimatedVisibility(
      visible = expanded,
      enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
      exit = shrinkVertically(tween(200, easing = FastOutSlowInEasing)) + fadeOut(tween(150)),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
      )
    }
  }
}

@Composable
fun SkyPlusMetricTile(
  label: String,
  value: String,
  icon: ImageVector,
  accentColor: Color,
  modifier: Modifier = Modifier,
  supportingText: String? = null,
) {
  SkyPlusPortfolioCard(
    modifier = modifier,
    contentPadding = PaddingValues(14.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(
        modifier = Modifier
          .size(30.dp)
          .clip(CircleShape)
          .background(accentColor.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = accentColor,
          modifier = Modifier.size(16.dp),
        )
      }
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Text(
      text = value,
      style = SkyPlusFonts.numericStyle(MaterialTheme.typography.titleLarge),
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (!supportingText.isNullOrBlank()) {
      Text(
        text = supportingText,
        style = MaterialTheme.typography.labelSmall,
        color = accentColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
fun SkyPlusSectionHeader(
  title: String,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
    )
    if (actionLabel != null && onAction != null) {
      TextButton(onClick = onAction) {
        Text(actionLabel, color = SkyPlusBrandColors.RoyalBlue)
      }
    }
  }
}

@Composable
fun SkyPlusAnimatedProgressBar(
  targetProgress: Float,
  barColor: Color,
  modifier: Modifier = Modifier,
  heightDp: Int = 8,
) {
  val clamped = targetProgress.coerceIn(0f, 1f)
  val animated by animateFloatAsState(
    targetValue = clamped,
    animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
    label = "skyplus_progress",
  )
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(heightDp.dp)
      .clip(RoundedCornerShape((heightDp / 2).dp))
      .background(MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth(animated)
        .height(heightDp.dp)
        .clip(RoundedCornerShape((heightDp / 2).dp))
        .background(barColor),
    )
  }
}

@Composable
fun SkyPlusPrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  enabled: Boolean = true,
) {
  val spacing = LocalSkyPlusSpacing.current
  Button(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(spacing.buttonRadius),
    colors = ButtonDefaults.buttonColors(
      containerColor = SkyPlusBrandColors.RoyalBlue,
      contentColor = SkyPlusBrandColors.PureWhite,
    ),
    modifier = modifier.height(48.dp),
  ) {
    if (icon != null) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(modifier = Modifier.width(8.dp))
    }
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
fun SkyPlusSecondaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  enabled: Boolean = true,
) {
  val spacing = LocalSkyPlusSpacing.current
  OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(spacing.buttonRadius),
    border = BorderStroke(1.dp, SkyPlusBrandColors.RoyalBlue.copy(alpha = 0.5f)),
    modifier = modifier.height(48.dp),
  ) {
    if (icon != null) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = SkyPlusBrandColors.RoyalBlue)
      Spacer(modifier = Modifier.width(8.dp))
    }
    Text(text, color = SkyPlusBrandColors.RoyalBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
fun SkyPlusEmptyState(
  icon: ImageVector,
  title: String,
  subtitle: String,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  SkyPlusPortfolioCard(modifier = modifier) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(
        modifier = Modifier
          .size(52.dp)
          .clip(CircleShape)
          .background(SkyPlusBrandColors.RoyalBlue.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = SkyPlusBrandColors.RoyalBlue,
          modifier = Modifier.size(26.dp),
        )
      }
      Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      if (actionLabel != null && onAction != null) {
        Spacer(modifier = Modifier.height(4.dp))
        SkyPlusPrimaryButton(text = actionLabel, onClick = onAction)
      }
    }
  }
}

@Composable
fun SkyPlusLoadingState(modifier: Modifier = Modifier) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = SkyPlusBrandColors.RoyalBlue)
  }
}

@Composable
fun SkyPlusErrorState(
  title: String,
  message: String,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      imageVector = Icons.Filled.ErrorOutline,
      contentDescription = null,
      tint = SkyPlusBrandColors.ErrorCoral,
      modifier = Modifier.size(48.dp),
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, color = SkyPlusBrandColors.ErrorCoral)
    Spacer(modifier = Modifier.height(6.dp))
    Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
  }
}
