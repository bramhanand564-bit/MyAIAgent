    private fun callLlamaAI(prompt: String) {
        Thread {
            try {
                val jsonBody = JSONObject().apply {
                    put("model", "meta-llama/llama-3.2-3b-instruct:free")
                    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                }

                val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("HTTP-Referer", "https://github.com/bramhanand564-bit")
                    .addHeader("X-Title", "MyAIAgent")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseData = response.body?.string()
                    if (response.isSuccessful && responseData != null) {
                        val jsonResponse = JSONObject(responseData)
                        val choices = jsonResponse.getJSONArray("choices")
                        val messageObj = choices.getJSONObject(0).getJSONObject("message")
                        val aiReply = messageObj.getString("content").trim()

                        runOnUiThread {
                            chatHistory.append("Agent: $aiReply\n")
                            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                        }
                    } else {
                        // यहाँ सर्वर का असली एरर दिखेगा
                        runOnUiThread {
                            chatHistory.append("Agent: Server Error: $responseData\n")
                            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                        }
                    }
                }
            } catch (e: Exception) {
                // यहाँ कोडिंग या एक्सेप्शन का असली रीज़न दिखेगा
                runOnUiThread {
                    chatHistory.append("Agent: Error: ${e.localizedMessage}\n")
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }.start()
    }
