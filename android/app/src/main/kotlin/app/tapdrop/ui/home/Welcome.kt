package app.tapdrop.ui.home

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import app.tapdrop.ui.Brand
import app.tapdrop.ui.TapCopy
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdType
import app.tapdrop.ui.design.AuroraBackdrop
import app.tapdrop.ui.design.DeviceHero
import app.tapdrop.ui.design.IconChip
import app.tapdrop.ui.design.LinkState
import app.tapdrop.ui.design.PrimaryButton
import app.tapdrop.ui.design.TdShapes
import app.tapdrop.ui.design.entrance

/** First run, before any Mac is paired: what TapDrop does, and the way in. */
@Composable
fun WelcomeScreen(onPair: () -> Unit) {
    val c = Td.colors
    val forgottenBy by app.tapdrop.TapDropApp.instance.prefs.forgottenBy.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        AuroraBackdrop(Modifier.fillMaxWidth().height(640.dp), intensity = 1.15f)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            DeviceHero(
                state = LinkState.Live,
                contentDescription = "A MacBook and a phone, linked by light",
                modifier = Modifier.widthIn(max = 380.dp).entrance(0),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Your phone, one tap from your Mac",
                style = TdType.display,
                color = c.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp).entrance(1).semantics { heading() },
            )
            Text(
                "Touch your phone's screen to the notch and whatever's on it lands on your Mac.",
                style = TdType.body.copy(lineBreak = LineBreak.Heading),
                color = c.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp).padding(top = 12.dp).entrance(2),
            )
            Spacer(Modifier.height(32.dp))
            Column(
                Modifier.widthIn(max = 400.dp).fillMaxWidth().entrance(3),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Feature(Icons.Rounded.TouchApp, Td.colors.text, TdShapes.Cookie9, "Tap to send", "${TapCopy.PRIMARY} ${TapCopy.HOLD}")
                Feature(Icons.Rounded.IosShare, Td.colors.text, TdShapes.Cookie6, "Share from any app", "Photos, links and files, right from the share sheet.")
                Feature(Icons.Rounded.Lock, Td.colors.text, TdShapes.Clover4, "Private by design", "End‑to‑end encrypted, over your own Wi‑Fi.")
            }
            Spacer(Modifier.height(32.dp))
            forgottenBy?.let { ForgottenNote(it, Modifier.widthIn(max = 400.dp).fillMaxWidth().padding(bottom = 16.dp)) }
            PrimaryButton(
                if (forgottenBy != null) "Pair again" else "Pair with your Mac",
                onPair,
                Modifier.widthIn(max = 400.dp).fillMaxWidth().entrance(4),
            )
            Text(
                "You'll need TapDrop on your Mac (macOS 15 or later).",
                style = TdType.caption,
                color = c.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp, bottom = 16.dp).entrance(5),
            )
        }
    }
}

/** Why the phone is back at the start: its Mac forgot it (and said so), so it was removed here too. */
@Composable
private fun ForgottenNote(macName: String, modifier: Modifier = Modifier) {
    val c = Td.colors
    Column(
        modifier
            .background(c.surface, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .border(1.dp, c.hairline, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text("$macName forgot this phone", style = TdType.label, color = c.text)
        Text(
            "It was removed on the Mac, so it's gone from here too. Pair again to keep sending.",
            style = TdType.bodySmall,
            color = c.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Feature(icon: ImageVector, accent: Color, shape: RoundedPolygon, title: String, body: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconChip(icon, accent, size = 44.dp, shape = shape, iconSize = 22.dp)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = TdType.body.copy(fontWeight = FontWeight.SemiBold), color = Td.colors.text)
            Text(body, style = TdType.bodySmall.copy(lineBreak = LineBreak.Heading), color = Td.colors.textSecondary)
        }
    }
}
