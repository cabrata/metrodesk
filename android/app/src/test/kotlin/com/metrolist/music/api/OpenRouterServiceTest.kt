package com.metrolist.music.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class OpenRouterServiceTest {
    @Test
    fun `non streaming requests explicitly disable proxy default streaming`() {
        for (baseUrl in listOf("https://openrouter.ai/api/v1/chat/completions", "https://proxy.example/v1/chat/completions")) {
            val request = buildTranslationRequest(
                text = "one\ntwo",
                targetLanguage = "Indonesian",
                model = "model",
                mode = "Translated",
                customSystemPrompt = "",
                baseUrl = baseUrl,
            )
            assertFalse(request.getValue("stream").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `custom endpoint translates through the real non streaming service`() {
        val url = System.getenv("METROLIST_AI_TEST_URL").orEmpty()
        val apiKey = System.getenv("METROLIST_AI_TEST_KEY").orEmpty()
        val model = System.getenv("METROLIST_AI_TEST_MODEL").orEmpty()
        assumeTrue("Live endpoint test requires explicit environment credentials", url.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank())

        val lines = runBlocking {
            OpenRouterService.translate(
                text = "The moon is bright\nThe night is quiet",
                targetLanguage = "Indonesian",
                apiKey = apiKey,
                baseUrl = url,
                model = model,
                mode = "Translated",
                maxRetries = 1,
            ).getOrThrow()
        }
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.isNotBlank() && !it.startsWith("data:") })
        assertTrue(lines[0].contains("bulan", ignoreCase = true))
        assertTrue(lines[1].contains("malam", ignoreCase = true))
    }

    @Test
    fun `translation parsing handles fenced and short responses`() {
        assertEquals(
            listOf("uno", ""),
            parseTranslationContent("```json\n[\"uno\"]\n```", 2).getOrThrow(),
        )
    }

    @Test
    fun `translation parsing handles the structured output object shape`() {
        assertEquals(
            listOf("uno", "dos"),
            parseTranslationContent("""{"lines": ["uno", "dos"]}""", 2).getOrThrow(),
        )
    }

    @Test
    fun `request uses structured outputs with a lines schema and strict parameter routing`() {
        val request =
            buildTranslationRequest(
                text = "one\ntwo",
                targetLanguage = "Spanish",
                model = "model",
                mode = "Translated",
                customSystemPrompt = "",
            )

        val responseFormat = request.getValue("response_format").jsonObject
        assertEquals("json_schema", responseFormat.getValue("type").jsonPrimitive.content)
        val schema = responseFormat.getValue("json_schema").jsonObject.getValue("schema").jsonObject
        assertEquals("object", schema.getValue("type").jsonPrimitive.content)
        val lines = schema.getValue("properties").jsonObject.getValue("lines").jsonObject
        assertEquals("array", lines.getValue("type").jsonPrimitive.content)
        assertEquals("string", lines.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(request.getValue("provider").jsonObject.getValue("require_parameters").jsonPrimitive.boolean)
    }

    @Test
    fun `openrouter-only provider routing is omitted for direct providers`() {
        val request =
            buildTranslationRequest(
                text = "one",
                targetLanguage = "Spanish",
                model = "mercury-2",
                mode = "Translated",
                customSystemPrompt = "",
                baseUrl = "https://api.inceptionlabs.ai/v1/chat/completions",
            )

        assertTrue("provider" !in request)
        assertTrue(request.containsKey("response_format"))
    }

    @Test
    fun `openai requests omit parameters gpt-5 models reject`() {
        val request =
            buildTranslationRequest(
                text = "one",
                targetLanguage = "Spanish",
                model = "gpt-5.6-luna",
                mode = "Translated",
                customSystemPrompt = "",
                baseUrl = "https://api.openai.com/v1/chat/completions",
            )

        assertTrue("max_tokens" !in request)
        assertTrue("temperature" !in request)
    }

    @Test
    fun `streaming romanization uses the romanization prompt`() {
        val request =
            buildTranslationRequest(
                text = "東京",
                targetLanguage = "English",
                model = "model",
                mode = "Romanized",
                customSystemPrompt = "",
                stream = true,
            )

        assertTrue(request.getValue("stream").jsonPrimitive.boolean)
        assertTrue(
            request
                .getValue("messages")
                .jsonArray[1]
                .jsonObject
                .getValue("content")
                .jsonPrimitive
                .content
                .startsWith("Romanize"),
        )
    }
}
