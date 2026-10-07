package com.brandonwallace.rocketlauncher

import com.brandonwallace.rocketlauncher.gl.Shaders
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Dumps every shader for offline validation with glslangValidator (only when SHADER_DUMP_DIR is set). */
class ShaderDumpTest {
    @Test fun dump() {
        val all = Shaders::class.java.declaredMethods.filter { it.name.startsWith("get") && it.returnType == String::class.java && it.parameterCount == 0 }
        assertTrue(all.size >= 15)
        for (m in all) {
            val src = m.invoke(Shaders) as String
            assertTrue("${m.name} must start with #version", src.startsWith("#version 300 es\n"))
        }
        val dir = System.getenv("SHADER_DUMP_DIR") ?: return
        File(dir).mkdirs()
        for (m in all) {
            val name = m.name.removePrefix("get")
            val ext = if (name.endsWith("VS")) "vert" else "frag"
            File(dir, "$name.$ext").writeText(m.invoke(Shaders) as String)
        }
    }
}
