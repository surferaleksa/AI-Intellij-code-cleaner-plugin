package org.example.demo

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class MyPluginAction : AnAction() {

    private val apiKey = "YOUR_GROQ_API_KEY_HERE"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val selectedText = editor.selectionModel.selectedText

        if (selectedText.isNullOrBlank()) {
            Messages.showMessageDialog(
                project,
                "Please select some code first!",
                "No Code Selected",
                Messages.getWarningIcon()
            )
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Refactoring with AI...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Sending code to Gemini..."
                val refactored = callGroqApi(selectedText)

                // Always interact with UI on the EDT thread
                ApplicationManager.getApplication().invokeLater {
                    if (refactored != null) {
                        WriteCommandAction.runWriteCommandAction(project) {
                            val start = editor.selectionModel.selectionStart
                            val end = editor.selectionModel.selectionEnd
                            editor.document.replaceString(start, end, refactored)
                        }
                    } else {
                        Messages.showMessageDialog(
                            project,
                            "Failed to get a response from Groq. Check your API key.",
                            "API Error",
                            Messages.getErrorIcon()
                        )
                    }
                }
            }
        })
    }

    private fun callGroqApi(code: String): String? {
        val prompt = """
        You are a code refactoring assistant. Clean up and refactor the following code to improve 
        readability, structure, and maintainability WITHOUT changing its functionality.
        - Improve variable and method naming
        - Clean up formatting and structure
        - Remove redundant code
        - Add brief comments where helpful
        Return ONLY the refactored code, no explanations, no markdown backticks.
        
        Code to refactor:
        $code
    """.trimIndent()

        val json = JsonObject().apply {
            addProperty("model", "llama-3.3-70b-versatile")
            addProperty("temperature", 0.3)
            add("messages", Gson().toJsonTree(listOf(
                mapOf("role" to "user", "content" to prompt)
            )))
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return null
            println("Groq response: $responseBody")
            val jsonResponse = Gson().fromJson(responseBody, JsonObject::class.java)
            jsonResponse
                .getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?.trim()
        } catch (ex: Exception) {
            println("Groq error: ${ex.message}")
            null
        }
    }
}