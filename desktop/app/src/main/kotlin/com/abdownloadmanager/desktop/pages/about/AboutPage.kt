package com.abdownloadmanager.desktop.pages.about

import androidx.compose.foundation.*
import com.abdownloadmanager.shared.util.ui.icon.MyIcons
import com.abdownloadmanager.shared.util.ui.myColors
import com.abdownloadmanager.shared.util.ui.theme.myTextSizes
import com.abdownloadmanager.shared.ui.widget.Text
import com.abdownloadmanager.desktop.utils.AppInfo
import com.abdownloadmanager.shared.util.ui.WithContentAlpha
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abdownloadmanager.shared.util.div
import com.abdownloadmanager.resources.Res
import ir.amirab.util.compose.resources.myStringResource

@Composable
fun AboutPage() {
    Box {
        BackgroundEffects()
        RenderAppInfo(Modifier)
    }
}

@Composable
private fun AppIconAndVersion(
    modifier: Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(
            horizontal = 24.dp,
            vertical = 8.dp,
        )
    ) {
        val shape = RoundedCornerShape(16.dp)
        Image(
            MyIcons.appIcon.rememberPainter(),
            null,
            Modifier
                .shadow(12.dp, shape, spotColor = myColors.primary)
                .clip(shape)
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(myColors.primary, myColors.secondary)
                    ),
                    shape
                )
                .background(myColors.surface)
                .padding(16.dp)
                .size(52.dp)
        )
        Spacer(Modifier.size(16.dp))
        Column(
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                AppInfo.displayName,
                fontSize = myTextSizes.lg,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            WithContentAlpha(0.75f) {
                Text(
                    myStringResource(
                        Res.string.version_n,
                        Res.string.version_n_createArgs(
                            value = AppInfo.version.toString(),
                        )
                    ),
                    fontSize = myTextSizes.base,
                )
            }
        }
    }
}

@Composable
private fun RenderAppInfo(
    modifier: Modifier,
) {
    Row(
        modifier.fillMaxSize(),
    ) {
        AppIconAndVersion(
            Modifier
                .width(250.dp)
                .fillMaxHeight()
                .wrapContentHeight(Alignment.CenterVertically)
        )
        Spacer(
            Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(myColors.onBackground.copy(0.15f))
        )
        AppDescription(
            Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun AppDescription(
    modifier: Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            myStringResource(Res.string.about_app_tagline),
            fontSize = myTextSizes.lg,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        WithContentAlpha(0.85f) {
            Text(
                myStringResource(Res.string.about_app_description),
                fontSize = myTextSizes.base,
                lineHeight = myTextSizes.base * 1.6f,
            )
        }
    }
}

@Composable
private fun BoxScope.BackgroundEffects() {
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .offset(x = (-50).dp, y = (-148).dp)
            .fillMaxWidth(0.5f)
            .height(250.dp)
            .blur(
                56.dp,
                edgeTreatment = BlurredEdgeTreatment.Unbounded
            )
            .clip(CircleShape)
            .background(
                myColors.primary / 0.15f
            )
    )
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .size(220.dp)
            .offset(x = (-64).dp, y = (+128).dp)
            .blur(
                56.dp,
                edgeTreatment = BlurredEdgeTreatment.Unbounded
            )
            .clip(CircleShape)
            .background(
                myColors.secondaryVariant / 0.15f
            )
    )
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .size(220.dp)
            .offset(x = 32.dp, y = (-32).dp)
            .blur(
                56.dp,
                edgeTreatment = BlurredEdgeTreatment.Unbounded
            )
            .clip(CircleShape)
            .background(
                myColors.secondary / 0.15f
            )
    )
}
