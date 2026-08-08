package com.dongholab.pagetuner.ui.splash

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkSoft

@Composable
fun PageTurnerSplashScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = EinkPanel,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = EinkSoft,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(2.dp, EinkInk),
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 36.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Classic Library Emblem Badge
                    Surface(
                        modifier = Modifier.size(92.dp),
                        color = EinkPanel,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, EinkInk),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.LocalLibrary,
                                contentDescription = "PageTurner Library Logo",
                                tint = EinkInk,
                                modifier = Modifier.size(54.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Text(
                        text = "PageTurner Library",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif,
                        color = EinkInk,
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = "A Quiet Monochrome Sanctuary for E-Paper Books",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Serif,
                        color = EinkMuted,
                    )

                    Spacer(Modifier.height(16.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.AutoStories, contentDescription = null, tint = EinkInk, modifier = Modifier.size(16.dp))
                        Text(
                            text = "Web Novels • Local EPUB/PDF/TXT • Offline Sync",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = EinkInk,
                        )
                    }

                    Spacer(Modifier.height(28.dp))

                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(3.dp),
                        color = EinkInk,
                        trackColor = EinkLine,
                    )

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "v1.2.0 • Digital B&W Library Edition",
                        style = MaterialTheme.typography.labelSmall,
                        color = EinkMuted,
                    )
                }
            }
        }
    }
}
