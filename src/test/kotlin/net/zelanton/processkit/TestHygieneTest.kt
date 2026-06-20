package net.zelanton.processkit

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.fail

/**
 * Guards against a Kotlin + JUnit Jupiter foot-gun: an expression-body `@Test`
 * whose last expression returns a non-`Unit` value (e.g. `= runBlocking { …
 * assertFailsWith { } }`) compiles to a **non-void** method, which JUnit Jupiter
 * silently never executes — a green build with zero coverage. This scans the
 * compiled test classes and fails loudly if any `@Test` method is non-void, so
 * the only fix is to make it return `Unit` (block body, trailing `Unit`, or
 * `: Unit =`).
 */
class TestHygieneTest {
    @Test
    fun `no @Test method is silently skipped by returning a non-void value`() {
        val root =
            Paths.get(
                this::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        if (!Files.isDirectory(root)) return // packaged as a jar (not the Gradle test layout) — skip
        val testAnnotation = Test::class.java
        val offenders = mutableListOf<String>()
        Files.walk(root).use { stream ->
            stream
                .filter { it.toString().endsWith(".class") }
                .forEach { path -> inspectClass(root, path, testAnnotation, offenders) }
        }
        if (offenders.isNotEmpty()) {
            fail(
                "Non-void @Test methods are silently skipped by JUnit Jupiter — make them return Unit " +
                    "(e.g. `fun x(): Unit = runBlocking { … }`):\n${offenders.sorted().joinToString("\n")}",
            )
        }
    }

    private fun inspectClass(
        root: Path,
        path: Path,
        testAnnotation: Class<out Annotation>,
        offenders: MutableList<String>,
    ) {
        val className =
            root
                .relativize(path)
                .toString()
                .removeSuffix(".class")
                .replace(path.fileSystem.separator, ".")
        // Don't initialize the class (no static side effects); skip anything that won't load.
        val clazz = runCatching { Class.forName(className, false, javaClass.classLoader) }.getOrNull() ?: return
        for (method in clazz.declaredMethods) {
            if (method.isAnnotationPresent(testAnnotation) && method.returnType != Void.TYPE) {
                offenders.add("${clazz.name}.${method.name} -> ${method.returnType.simpleName}")
            }
        }
    }
}
