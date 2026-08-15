package tw.scooter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tw.scooter.R
import tw.scooter.ui.theme.AppearanceMode
import tw.scooter.ui.theme.ScooterColors

@Composable
fun SettingsDrawer(
    duckingEnabled: Boolean,
    appearance: AppearanceMode,
    onDuckingChanged: (Boolean) -> Unit,
    onAppearanceChanged: (AppearanceMode) -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.settings),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 20.dp),
        )

        Text(
            text = stringResource(R.string.settings_sync),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSync)
                .padding(vertical = 14.dp),
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_ducking),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = duckingEnabled, onCheckedChange = onDuckingChanged)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Text(
            text = stringResource(R.string.settings_appearance),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
        )
        AppearanceMode.entries.forEach { mode ->
            val labelRes = when (mode) {
                AppearanceMode.SYSTEM -> R.string.appearance_system
                AppearanceMode.DARK -> R.string.appearance_dark
                AppearanceMode.LIGHT -> R.string.appearance_light
            }
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.bodyMedium,
                color = if (mode == appearance) ScooterColors.Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAppearanceChanged(mode) }
                    .padding(vertical = 12.dp),
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Text(
            text = stringResource(R.string.disclaimer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}
