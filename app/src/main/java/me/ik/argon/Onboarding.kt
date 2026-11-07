package me.ik.argon

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource

/**
 * First-run onboarding (chrome v3). Three calm pages - welcome, privacy
 * defaults, search engine - with restrained spring motion and generous
 * spacing (macOS/Deepin-inspired: quiet surfaces, hairlines, no mascots).
 * Choices write the same SharedPreferences keys the settings screen uses,
 * so onboarding and settings never disagree.
 */
@Composable
fun OnboardingScreen(
    adblock: Boolean,
    searchEngine: SearchEngine,
    onAdblockChange: (Boolean) -> Unit,
    onEngineChange: (SearchEngine) -> Unit,
    onDone: () -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }
    // Mirror the on-screen Back button: system back steps back a page
    // instead of dropping the user out of first run.
    androidx.activity.compose.BackHandler(enabled = page > 0) { page -= 1 }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ArgonColors.Bg)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Spacer(Modifier.weight(1f))
            if (page < 2) {
                Text(
                    "Skip",
                    color = ArgonColors.Text3,
                    style = ArgonType.BodySmall,
                    modifier =
                        Modifier.clip(RoundedCornerShape(ArgonRadii.Control))
                            .clickable(onClickLabel = "Skip introduction", role = Role.Button) { onDone() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                (slideInHorizontally(spring()) { it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally(spring()) { -it / 3 } + fadeOut())
            },
            label = "onboarding",
        ) { p ->
            when (p) {
                0 -> WelcomePage()
                1 -> PrivacyPage(adblock, onAdblockChange)
                else -> EnginePage(searchEngine, onEngineChange)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { i ->
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (i == page) ArgonColors.Accent else ArgonColors.Hairline),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (page > 0) {
                OnboardingButton(label = "Back", primary = false) { page -= 1 }
                Spacer(Modifier.width(12.dp))
            }
            OnboardingButton(
                label = if (page < 2) "Continue" else "Start browsing",
                primary = true,
            ) {
                if (page < 2) page += 1 else onDone()
            }
        }
    }
}

@Composable
private fun OnboardingButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(if (primary) ArgonColors.Accent else ArgonColors.SurfaceHigh)
                .padding(horizontal = 24.dp)
                .selectable(selected = false, enabled = true, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
    ) {
        Text(
            label,
            color = if (primary) ArgonColors.OnAccent else ArgonColors.Text,
            style = ArgonType.BodySmall.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun PageHeader(title: String, body: String) {
    Text(
        title,
        color = ArgonColors.Text,
        style = ArgonType.Hero,
        letterSpacing = (-0.5).sp,
    )
    Spacer(Modifier.height(14.dp))
    Text(body, color = ArgonColors.Text2, style = ArgonType.BodySmall)
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun WelcomePage() {
    Column {
        androidx.compose.foundation.Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(24.dp))
        PageHeader(
            "A quieter,\nfaster browser.",
            "Argon runs on the Servo engine, blocks ads and trackers by default, and keeps the controls out of your way. Three quick choices and you're in.",
        )
        FeatureLine("Ad and tracker blocking", "On by default, no account needed.")
        Spacer(Modifier.height(20.dp))
        FeatureLine("Your search, your choice", "Pick Google, DuckDuckGo, Brave or Bing, and switch anytime.")
        Spacer(Modifier.height(20.dp))
        FeatureLine("Your data stays here", "History and bookmarks never leave the device.")
    }
}

@Composable
private fun FeatureLine(title: String, subtitle: String) {
    Column {
        Text(title, color = ArgonColors.Text, style = ArgonType.Heading)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, color = ArgonColors.Text2, style = ArgonType.Caption)
    }
}

@Composable
private fun PrivacyPage(adblock: Boolean, onAdblockChange: (Boolean) -> Unit) {
    Column {
        PageHeader(
            "Private by default.",
            "Blocking happens on the device against a bundled list. Nothing you browse is sent to us - there is no us.",
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ArgonRadii.Card))
                    .background(ArgonColors.BgRaise)
                    .border(1.dp, ArgonColors.Hairline, RoundedCornerShape(ArgonRadii.Card))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Ad and tracker blocking", color = ArgonColors.Text, style = ArgonType.Heading)
                Spacer(Modifier.height(2.dp))
                Text("Recommended", color = ArgonColors.Text3, style = ArgonType.Caption)
            }
            Switch(
                checked = adblock,
                onCheckedChange = onAdblockChange,
                colors =
                    SwitchDefaults.colors(
                        checkedTrackColor = ArgonColors.Accent,
                        uncheckedTrackColor = ArgonColors.SurfaceHigh,
                    ),
            )
        }
    }
}

@Composable
private fun EnginePage(searchEngine: SearchEngine, onEngineChange: (SearchEngine) -> Unit) {
    Column {
        PageHeader(
            "Pick your search.",
            "Used when you type something that isn't an address. You can change this any time in Settings.",
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ArgonColors.Surface),
        ) {
            SearchEngine.entries.forEachIndexed { i, engine ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .selectable(
                                selected = engine == searchEngine,
                                onClick = { onEngineChange(engine) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 20.dp),
                ) {
                    RadioButton(
                        selected = engine == searchEngine,
                        onClick = null,
                        colors =
                            RadioButtonDefaults.colors(
                                selectedColor = ArgonColors.Accent,
                                unselectedColor = ArgonColors.Text3,
                            ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(engine.label, color = ArgonColors.Text, style = ArgonType.BodySmall)
                }
                if (i < SearchEngine.entries.size - 1) {
                    Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(1.dp).background(ArgonColors.Hairline))
                }
            }
        }
    }
}

