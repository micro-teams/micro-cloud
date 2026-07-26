/*
 *  Description: An unknown request path must return a clean 404 (not a 500 with a stack trace). This
 *               guards the GlobalErrorHandler mapping of NoResourceFoundException — a public API's
 *               callers need to tell "wrong path" apart from "server broke".
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@AutoConfigureMockMvc
class UnknownPathTest @Autowired constructor(private val mockMvc: MockMvc) {
    @Test
    fun unknownPathReturns404NotFound() {
        mockMvc
            .perform(get("/no-such-endpoint"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(404))
    }
}
