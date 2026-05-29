package com.kinetix.gateway

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldNotBe
import io.ktor.server.application.Application

/**
 * Guards against a class of deploy-blocking regression: when a Ktor module function
 * referenced by `ktor.application.modules` in application.conf is moved to a different
 * file (changing its compiled `…Kt` class name) without updating the config, Ktor fails
 * at startup with "Module function cannot be found for the fully qualified name …".
 *
 * The acceptance/integration tests install modules directly (e.g. `application { devModule() }`)
 * so they never exercise application.conf's FQN-based resolution — this test does.
 */
class ApplicationConfModuleResolutionTest : FunSpec({

    test("every module declared in application.conf resolves to an Application module function") {
        val config = ConfigFactory.parseResources("application.conf")
        val modules = config.getStringList("ktor.application.modules")
        modules.shouldNotBeEmpty()

        modules.forEach { fqn ->
            val splitAt = fqn.lastIndexOf('.')
            val className = fqn.substring(0, splitAt)
            val methodName = fqn.substring(splitAt + 1)

            val clazz = withClue("module '$fqn' references class '$className' which must exist on the classpath") {
                Class.forName(className)
            }
            val method = clazz.declaredMethods.firstOrNull { m ->
                m.name == methodName &&
                    m.parameterTypes.isNotEmpty() &&
                    m.parameterTypes[0] == Application::class.java
            }
            withClue("module '$fqn' must resolve to a `fun Application.$methodName(…)` in $className") {
                method shouldNotBe null
            }
        }
    }
})
