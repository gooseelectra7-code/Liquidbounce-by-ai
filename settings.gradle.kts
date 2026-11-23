/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2023 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "net.fabricmc.fabric-loom") {
                useModule("net.fabricmc:fabric-loom:${requested.version}")
            }
        }
    }

    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "FabricMC"
                    url = uri("https://maven.fabricmc.net/")
                }
            }
            filter {
                includeModule("fabric-loom", "fabric-loom.gradle.plugin")
                includeGroupByRegex("net.fabricmc.*")
            }
        }
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "LiquidBounce"

// CometRenderer (HUD render): built from local source in CometRenderer-main and published to the
// local Maven repo (`mavenLocal()`), then consumed by coordinates `com.ferra13671:crm:26.1-fabric-2.8`
// in build.gradle.kts. Composite `includeBuild` is intentionally NOT used: the CRM artifactId (`crm`)
// differs from its Gradle project name, so auto-substitution misses, and we specifically need the
// shaded+remapped `crm` jar (core CometRenderer + gl-texture-utils bundled).
// Rebuild after editing the renderer:
//   CometRenderer-main\gradlew.bat :minecraft:minecraft-26.1-fabric:publishToMavenLocal
