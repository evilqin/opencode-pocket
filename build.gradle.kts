// 顶层构建脚本：只声明插件版本，具体配置在各模块。
// 注意：AGP 9 起自带 Kotlin 支持，不再声明 org.jetbrains.kotlin.android。
plugins {
    id("com.android.application") version "9.3.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
