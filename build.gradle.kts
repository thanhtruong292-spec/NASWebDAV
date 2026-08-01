// Top-level build file. Plugin DSL is delegated to :app via the version catalog
// (`gradle/libs.versions.toml`). All plugins applied here would also be applied
// to each module that declares them via `alias(libs.plugins.<id>)`.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}