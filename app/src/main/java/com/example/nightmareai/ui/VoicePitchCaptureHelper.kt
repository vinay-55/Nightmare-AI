package com.example.nightmareai.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Streams live spoken pitch transcripts (both partial and final results) into the
 * speculative truth-verification pipeline using Android's native [SpeechRecognizer].
 */
class VoicePitchCaptureHelper(
    private val context: Context,
    private val onPartialTranscript: (String) -> Unit,
    private val onFinalTranscript: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit,
    private val onErrorMessage: (String) -> Unit
) {

    private var recognizer: SpeechRecognizer? = null
    private var listening: Boolean = false

    fun isListening(): Boolean = listening

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onErrorMessage("On-device speech recognition is unavailable on this device.")
            return
        }
        stopListening()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                onListeningStateChanged(true)
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                listening = false
                onListeningStateChanged(false)
            }

            override fun onError(error: Int) {
                listening = false
                onListeningStateChanged(false)
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_CLIENT) {
                    onErrorMessage("Voice capture interrupted (code $error).")
                }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                onListeningStateChanged(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    onFinalTranscript(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    onPartialTranscript(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        sr.startListening(intent)
    }

    fun stopListening() {
        listening = false
        onListeningStateChanged(false)
        recognizer?.destroy()
        recognizer = null
    }
}
