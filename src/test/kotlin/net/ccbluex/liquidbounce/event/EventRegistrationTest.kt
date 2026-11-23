/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
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
package net.ccbluex.liquidbounce.event

import net.ccbluex.liquidbounce.annotations.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the one thing about the event system that fails at *runtime* rather than at compile time.
 *
 * Declaring an event and handling it both compile fine while the class is missing from
 * [ALL_EVENT_CLASSES]; the registry lookup then throws inside a module's static initializer, which
 * surfaces as `ExceptionInInitializerError` during `ModuleManager.registerInbuilt` and leaves the
 * whole client without any modules. That is exactly what an unregistered `PlayerInteractEntityEvent`
 * did — a one-line omission that cost a play session and looked like a crash from somewhere else
 * entirely.
 *
 * The class list is read off the compiled output rather than through a classpath scanner, so this
 * needs no new dependency, and off the *event package* only, because that is where the convention
 * says events live.
 */
class EventRegistrationTest {

    @Test
    fun `every declared event is registered`() {
        val declared = declaredEventClasses()

        // A path change or an unbuilt module would otherwise turn this test into a silent pass.
        assertTrue(
            declared.size > MINIMUM_PLAUSIBLE_EVENTS,
            "Only ${declared.size} event classes found — the compiled output was probably not " +
                "where this test looked, so nothing was actually checked."
        )

        val registered = ALL_EVENT_CLASSES.toHashSet()
        val missing = declared.filterNot { it in registered }.map { it.simpleName }.sorted()

        assertEquals(
            emptyList<String>(),
            missing,
            "Event classes missing from ALL_EVENT_CLASSES. Handling any of them throws at class " +
                "initialization time, which takes the whole client down at startup."
        )
    }

    @Test
    fun `registered events carry a unique tag`() {
        val tags = ALL_EVENT_CLASSES.map { eventClass ->
            val tag = eventClass.getAnnotation(Tag::class.java)
            requireNotNull(tag) { "${eventClass.simpleName} has no @Tag; Event.kt dereferences it" }
            tag.name to eventClass.simpleName
        }

        val duplicates = tags.groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }

        assertEquals(
            emptyMap<String, List<String>>(),
            duplicates,
            "Tags name events in configs and in the script API; a collision makes one of them " +
                "unreachable and silently rebinds the other."
        )
    }

    /**
     * Every concrete [Event] in the events package, loaded without initializing it — the classes
     * reference Minecraft types that must not be touched outside a running client.
     */
    private fun declaredEventClasses(): List<Class<out Event>> {
        val root = eventsOutputDirectory() ?: return emptyList()
        val loader = EventRegistrationTest::class.java.classLoader

        return root.listFiles { file -> file.isFile && file.extension == "class" }
            .orEmpty()
            .map { it.nameWithoutExtension }
            // Kotlin's synthetic holders and nested types are not events in their own right.
            .filterNot { it.endsWith("Kt") || '$' in it }
            .sorted()
            .mapNotNull { name ->
                runCatching { Class.forName("$EVENTS_PACKAGE.$name", false, loader) }.getOrNull()
            }
            .filter { Event::class.java.isAssignableFrom(it) && !it.isInterface }
            .filterNot { java.lang.reflect.Modifier.isAbstract(it.modifiers) }
            .map {
                @Suppress("UNCHECKED_CAST")
                it as Class<out Event>
            }
    }

    private fun eventsOutputDirectory(): File? = CANDIDATE_OUTPUTS
        .map { File(it, EVENTS_PACKAGE.replace('.', '/')) }
        .firstOrNull { it.isDirectory }

    private companion object {
        const val EVENTS_PACKAGE = "net.ccbluex.liquidbounce.event.events"

        /** Where Gradle puts the compiled main sources; the first that exists wins. */
        val CANDIDATE_OUTPUTS = listOf(
            "build/classes/kotlin/main",
            "build/classes/java/main"
        )

        /** Far below the real count, high enough that an empty scan cannot pass as success. */
        const val MINIMUM_PLAUSIBLE_EVENTS = 50
    }
}
