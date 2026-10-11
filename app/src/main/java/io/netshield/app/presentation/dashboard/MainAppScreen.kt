package io.netshield.app.presentation.dashboard

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.netshield.app.presentation.components.AppSelectionList
import io.netshield.app.presentation.components.AppSettingsDialog
import io.netshield.app.presentation.components.CustomRulesTabContent
import io.netshield.app.presentation.components.FeatureToggleCard
import io.netshield.app.presentation.components.ListDisabledPlaceholder
import io.netshield.app.presentation.components.SystemStatusHeader
import io.netshield.app.presentation.viewmodel.AppListViewModel
import kotlinx.coroutines.launch

enum class AppFilter {
    ALL,
    AD_BLOCK_ENABLED,
    INTERNET_BLOCKED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: AppListViewModel,
    modifier: Modifier = Modifier,
    onAppPolicyChanged: () -> Unit = {},
    onCustomRulesChanged: () -> Unit = {}
) {
    val appList by viewModel.appList.collectAsState()
    val isVpnActive by viewModel.isVpnActive.collectAsState()
    val isNetworkAdsBlocked by viewModel.isNetworkAdsBlocked.collectAsState()
    val isAdultContentBlocked by viewModel.isAdultContentBlocked.collectAsState()
    val totalBlockedCount by viewModel.blockedCount.collectAsState()
    val recent25MinCount by viewModel.recent25MinCount.collectAsState()
    val activeDurationText by viewModel.activeDurationText.collectAsState()

    var selectedAppPackage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(AppFilter.ALL) }
    var filterMenuExpanded by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    val alwaysBlockedDomains by viewModel.alwaysBlockedDomains.collectAsState()
    var customDomainInput by remember { mutableStateOf("") }

    val filteredApps by remember(appList, searchQuery, selectedFilter) {
        derivedStateOf {
            appList.filter { app ->
                val matchesSearch = searchQuery.isBlank() ||
                        app.name.contains(searchQuery, ignoreCase = true) ||
                        app.packageName.contains(searchQuery, ignoreCase = true)

                val matchesFilter = when (selectedFilter) {
                    AppFilter.ALL -> true
                    AppFilter.AD_BLOCK_ENABLED -> app.isAdBlockEnabled
                    AppFilter.INTERNET_BLOCKED -> app.isInternetBlocked
                }
                matchesSearch && matchesFilter
            }
        }
    }

    val context = LocalContext.current

    LaunchedEffect(isNetworkAdsBlocked) {
        if (isNetworkAdsBlocked) {
            viewModel.loadInstalledApps()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "NetShield Dashboard",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            try {
                                val feedbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/FardeenSayyed/NetShield/issues/new"))
                                context.startActivity(feedbackIntent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Feedback page: https://github.com/FardeenSayyed/NetShield", Toast.LENGTH_LONG).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Feedback,
                            contentDescription = "Submit Feedback & Support",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(imageVector = Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(imageVector = Icons.Default.FilterList, contentDescription = "Custom Rules & Stats") },
                    label = { Text("Rules & Stats") }
                )
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(horizontal = 20.dp)
                    ) {
                        SystemStatusHeader(
                            isActive = isVpnActive,
                            recent25MinCount = recent25MinCount,
                            activeDurationText = activeDurationText
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Security Controls",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                        )
                        FeatureToggleCard(
                            title = "Block Ads Network-Wide",
                            description = "Blocks ads across apps and websites using NetShield's network protection.",
                            icon = Icons.Default.Settings,
                            isChecked = isNetworkAdsBlocked,
                            onCheckedChange = { viewModel.toggleAdBlocking(it) },
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FeatureToggleCard(
                            title = "Block Adult Content",
                            description = "Uses family-safe DNS filtering to help block adult websites.",
                            icon = Icons.Default.Lock,
                            isChecked = isAdultContentBlocked,
                            onCheckedChange = { viewModel.toggleAdultContentBlocking(it) },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Protected Applications",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("Search apps...") },
                                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = "Search") },
                                shape = RoundedCornerShape(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box {
                                Surface(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { filterMenuExpanded = true },
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.FilterList,
                                            contentDescription = "Filter apps",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = filterMenuExpanded,
                                    onDismissRequest = { filterMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(text = { Text("All") }, onClick = { selectedFilter = AppFilter.ALL; filterMenuExpanded = false })
                                    DropdownMenuItem(text = { Text("Ad Blocker Enabled") }, onClick = { selectedFilter = AppFilter.AD_BLOCK_ENABLED; filterMenuExpanded = false })
                                    DropdownMenuItem(text = { Text("Internet Blocked") }, onClick = { selectedFilter = AppFilter.INTERNET_BLOCKED; filterMenuExpanded = false })
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = when (selectedFilter) {
                                AppFilter.ALL -> "All apps"
                                AppFilter.AD_BLOCK_ENABLED -> "Ad blocker enabled"
                                AppFilter.INTERNET_BLOCKED -> "Internet access blocked"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                        )
                        AnimatedContent(
                            targetState = isNetworkAdsBlocked,
                            label = "AppListTransition",
                        ) { adsEnabled ->
                            if (adsEnabled) {
                                AppSelectionList(
                                    appList = filteredApps,
                                    onAppClick = { app -> selectedAppPackage = app.packageName },
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                ListDisabledPlaceholder()
                            }
                        }
                    }
                }
                1 -> {
                    CustomRulesTabContent(
                        alwaysBlockedDomains = alwaysBlockedDomains,
                        domainInput = customDomainInput,
                        totalBlockedCount = totalBlockedCount,
                        recent25MinCount = recent25MinCount,
                        activeDurationText = activeDurationText,
                        onDomainInputChange = { customDomainInput = it },
                        onAddBlock = {
                            viewModel.addAlwaysBlockedDomain(customDomainInput)
                            customDomainInput = ""
                            onCustomRulesChanged()
                        },
                        onRemoveBlock = {
                            viewModel.removeAlwaysBlockedDomain(it)
                            onCustomRulesChanged()
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(horizontal = 20.dp)
                    )
                }
            }
        }
    }

    selectedAppPackage?.let { packageName ->
        val currentApp = appList.firstOrNull { it.packageName == packageName }
        if (currentApp != null) {
            AppSettingsDialog(
                app = currentApp,
                onDismiss = { selectedAppPackage = null },
                onAdBlockChanged = { enabled ->
                    if (!currentApp.isInternetBlocked) {
                        viewModel.setAdBlock(packageName, enabled)
                        onAppPolicyChanged()
                    }
                },
                onInternetBlockChanged = { blocked ->
                    viewModel.setInternetBlocked(packageName, blocked)
                    onAppPolicyChanged()
                }
            )
        }
    }
}
