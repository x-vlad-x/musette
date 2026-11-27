// SPDX-License-Identifier: GPL-3.0-or-later
// Musette — a native GNOME (GTK4 + libadwaita) front-end over SimpMusic's own core.
//
// Lives outside the SimpMusic checkout (update-musette.sh prepares that checkout) and is wired in by
// patches/01-gtk-module.patch. Reuses composeApp's ViewModels and the core modules (YouTube Music
// scraper, streams, Room database, mpv player); only the UI is new, written with java-gi.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // java-gi is compiled for Java 25 and uses the FFM API; the phone runs a Java 25 JRE.
    jvmToolchain(25)
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":composeApp"))
                implementation(project(":data"))
                implementation(project(":domain"))
                implementation(project(":common"))
                implementation("org.java-gi:adw:1.0.0-RC3")
                // WebKitGTK 6.0 for the Google sign-in page (the phone ships libwebkitgtk-6.0-4).
                implementation("org.java-gi:webkit:1.0.0-RC3")
                implementation(project.dependencies.platform(libs.koin.bom))
                implementation(libs.koin.core)
                // SimpMusic's translated strings (composeApp's Res, made public by patches/01).
                implementation(libs.components.resources)
                // Bundled in Android, missing on the JVM: PipePipeExtractor needs org.json (desktopApp adds it too).
                implementation(libs.org.json)
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:${libs.versions.lifecycle.get()}")
                // Skia runtime for the phone: composeApp only carries the build host's (x64) one,
                // and its ViewModels touch ImageBitmap.
                implementation(libs.compose.linux.arm64)
            }
        }
    }
}

tasks.named<ProcessResources>("jvmProcessResources") {
    from(projectDir.parentFile) {
        include("VERSION", "upstream.env")
        into("musette")
    }
}

// build/gtk-dist/lib: the app jar plus every runtime jar, run with `java -cp 'lib/*'`.
// A plain classpath keeps META-INF/services intact (the GTK main dispatcher is found through it).
// Jars are prefixed with their group: org.jetbrains.androidx and androidx publish same-named files.
val gtkDist by tasks.registering(Sync::class) {
    group = "distribution"
    val runtime = configurations.named("jvmRuntimeClasspath")
    val uniqueNames =
        runtime.map { cfg ->
            cfg.incoming.artifacts.artifacts.associate { artifact ->
                val id = artifact.id.componentIdentifier
                val name = if (id is ModuleComponentIdentifier) "${id.group}--${artifact.file.name}" else artifact.file.name
                artifact.file.absolutePath to name
            }
        }
    from(tasks.named("jvmJar"))
    from(runtime)
    eachFile { uniqueNames.get()[file.absolutePath]?.let { name = it } }
    into(layout.buildDirectory.dir("gtk-dist/lib"))
}
