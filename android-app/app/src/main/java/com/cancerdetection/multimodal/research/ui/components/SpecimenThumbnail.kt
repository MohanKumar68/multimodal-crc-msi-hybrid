package com.cancerdetection.multimodal.research.ui.components

import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cancerdetection.multimodal.research.data.storage.SpecimenIngestionManager
import com.cancerdetection.multimodal.research.domain.model.SpecimenCategory
import com.cancerdetection.multimodal.research.domain.model.SpecimenImage
import com.cancerdetection.multimodal.research.ui.theme.PathologyCardBorder
import com.cancerdetection.multimodal.research.ui.theme.PathologyNavy
import com.cancerdetection.multimodal.research.ui.theme.PathologySurfaceVariant
import com.cancerdetection.multimodal.research.ui.theme.PathologyTeal
import com.cancerdetection.multimodal.research.ui.theme.PathologyTextSecondary

@Composable
fun SpecimenThumbnailItem(
    image: SpecimenImage,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isWsi = image.category == SpecimenCategory.WHOLE_SLIDE_IMAGE

    Box(
        modifier = modifier
            .width(140.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(PathologySurfaceVariant)
            .border(1.dp, PathologyCardBorder, RoundedCornerShape(8.dp))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(105.dp)
                    .background(if (isWsi) Color(0xFF0F1D38) else Color(0xFFE2E8F0)),
                contentAlignment = Alignment.Center
            ) {
                if (isWsi) {
                    // Whole Slide Image Badge representation (avoids loading multi-GB slide into RAM)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Layers,
                            contentDescription = "Whole-Slide Specimen",
                            tint = PathologyTeal,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(PathologyTeal.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = (image.extension.ifBlank { "WSI" }).uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White
                                )
                            )
                        }
                        if (image.fileSizeBytes > 0) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = SpecimenIngestionManager.formatFileSize(image.fileSizeBytes),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 9.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            )
                        }
                    }
                } else {
                    // Standard Downsampled Ordinary Tile Preview via Coil
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(Uri.parse(image.uriString))
                            .crossfade(true)
                            .size(256, 256)
                            .error(com.cancerdetection.multimodal.research.R.drawable.ic_launcher_foreground)
                            .fallback(com.cancerdetection.multimodal.research.R.drawable.ic_launcher_foreground)
                            .build(),
                        contentDescription = image.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Remove button
                if (onRemove != null) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xCC0F1D38))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove Specimen",
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            // Caption details
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp)
            ) {
                Text(
                    text = image.displayName,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = PathologyNavy
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isWsi) "Whole-Slide Image" else if (image.fileSizeBytes > 0) SpecimenIngestionManager.formatFileSize(image.fileSizeBytes) else "H&E Tile",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 9.sp,
                        color = PathologyTextSecondary
                    ),
                    maxLines = 1
                )
            }
        }
    }
}
