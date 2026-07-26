/*
 *  Description: The template catalog is enumerated from the templates directory: an image dropped at
 *               <dir>/lxc/<name>/<name>.tar.zst shows up as template <name> (kind lxc). No config.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.api

import java.nio.file.Files
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureMockMvc
class TemplatesFromDirTest
@Autowired
constructor(
    private val mockMvc: MockMvc,
    @Value("\${microcloud.superadmin-password}") private val superadminPassword: String,
) {
    companion object {
        // A temp templates dir with one lxc image: <dir>/lxc/debian-x/debian-x.tar.zst
        @JvmStatic
        @DynamicPropertySource
        fun templatesDir(registry: DynamicPropertyRegistry) {
            val dir = Files.createTempDirectory("mc-templates")
            val img = dir.resolve("lxc").resolve("debian-x").resolve("debian-x.tar.zst")
            Files.createDirectories(img.parent)
            Files.writeString(img, "not-a-real-image")
            registry.add("microcloud.templates-dir") { dir.toString() }
        }
    }

    @Test
    fun catalogIsEnumeratedFromTheDirectory() {
        val token =
            JSONObject(
                    mockMvc
                        .perform(
                            post("/superadmin/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"password":"$superadminPassword"}""")
                        )
                        .andReturn()
                        .response
                        .contentAsString
                )
                .getString("token")

        mockMvc
            .perform(get("/machine/template").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[?(@.name=='debian-x')]").exists())
            .andExpect(jsonPath("$.items[?(@.name=='debian-x' && @.kind=='lxc')]").exists())
    }
}
