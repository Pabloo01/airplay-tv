package io.github.jqssun.airplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.ui.design.RoundIconButton
import io.github.jqssun.airplay.ui.design.card
import io.github.jqssun.airplay.ui.theme.Atv
import io.github.jqssun.airplay.ui.theme.AtvType
import io.github.jqssun.airplay.viewmodel.MainViewModel

/** Receiver logs: title with export / clear on top, the log lines in a panel below. */
@Composable
fun LogsScreen(viewModel: MainViewModel) {
    val logs by viewModel.logs.collectAsState()
    val listState = rememberLazyListState()
    val exportFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { exportFocus.requestFocusUntilLanded() }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Atv.Space4)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Atv.Space4),
        ) {
            Text(stringResource(R.string.tab_logs), style = AtvType.Title, modifier = Modifier.weight(1f))
            RoundIconButton(
                Icons.Outlined.Share,
                stringResource(R.string.btn_export),
                viewModel::exportLogs,
                modifier = Modifier.focusRequester(exportFocus),
                size = 48.dp,
            )
            RoundIconButton(Icons.Outlined.Delete, stringResource(R.string.btn_clear), viewModel::clearLogs, size = 48.dp)
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().card(Atv.ShapeLg, Atv.Butaca, border = Atv.Pasillo)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Atv.Space4),
                verticalArrangement = Arrangement.spacedBy(Atv.Space1),
            ) {
                items(logs) {
                    Text(it, style = LogLine, color = Atv.InkMuted)
                }
            }
        }
    }
}

private val LogLine = AtvType.Footnote.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal)
