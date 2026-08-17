// 根构建脚本：只声明插件版本，具体应用在 app 模块
plugins {
    // AGP 9.x：内置 Kotlin 支持（无需再应用 org.jetbrains.kotlin.android）
    id("com.android.application") version "9.3.1" apply false
    // Compose 编译器插件，版本跟随 Kotlin
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
