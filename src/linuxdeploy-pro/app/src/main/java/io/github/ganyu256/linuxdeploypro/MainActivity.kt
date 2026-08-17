package io.github.ganyu256.linuxdeploypro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ 全面屏：状态栏/导航栏透明，由 Compose 自行处理边距
        enableEdgeToEdge()
        setContent {
            App()
        }
    }
}
