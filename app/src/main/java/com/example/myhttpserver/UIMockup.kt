package com.example.myhttpserver

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Preview
@Composable
fun TitleNoBackgroundPreview() {
    Surface(modifier = Modifier.fillMaxWidth().height(150.dp), color = Color.Black) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Gestor Torrent",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(modifier = Modifier.width(100.dp), thickness = 2.dp, color = Color(0xFFE60012))
        }
    }
}

@Preview
@Composable
fun TitleRoundedPreview() {
    Surface(modifier = Modifier.fillMaxWidth().height(150.dp), color = Color.Black) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE60012)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "Gestor Torrent",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Preview
@Composable
fun TitleRectangularPreview() {
    Surface(modifier = Modifier.fillMaxWidth().height(150.dp), color = Color.Black) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                color = Color(0xFFE60012)
            ) {
                Text(
                    text = "Gestor Torrent",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
        }
    }
}
