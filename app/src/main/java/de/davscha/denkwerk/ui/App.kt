package de.davscha.denkwerk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Breiter Bildschirm (Tablet): Chatliste fest links, Chat rechts.
 * Schmaler Bildschirm (Handy): Chatliste als Schublade über das Menü.
 */
@Composable
fun App(vm: AppViewModel) {
    BackHandler(enabled = vm.screen != Screen.Chat) { vm.back() }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 840.dp
        when (vm.screen) {
            Screen.Settings -> SettingsScreen(vm)
            Screen.Presets -> PresetsScreen(vm)
            Screen.Chat -> if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(vm, onPicked = {}, modifier = Modifier.width(300.dp).fillMaxHeight())
                    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
                    Box(Modifier.weight(1f)) { ChatScreen(vm, onMenu = null) }
                }
            } else {
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
                ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerContainerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.width(300.dp),
                        ) {
                            Sidebar(vm, onPicked = { scope.launch { drawer.close() } }, modifier = Modifier.fillMaxHeight())
                        }
                    },
                ) {
                    ChatScreen(vm, onMenu = { scope.launch { drawer.open() } })
                }
            }
        }
    }
}
