package com.twistedphone.tflite

import android.content.Context
import com.twistedphone.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Utility to inspect/ dump TensorFlow Lite interpreter tensor names, shapes and dtypes.
 *
 * Usage:
 *  - call TFLiteInspector.dumpModelsInAppModelsDir(context)
 *  - or call dumpInterpreter(context, interpreter, "my-decoder")
 *
 * Output:
 *  - logs via FileLogger
 *  - writes a file to: <filesDir>/tflite_signatures.txt
 */
object TFLiteInspector {
    private const val TAG = "TFLiteInspector"

    // Map the file into a ByteBuffer for Interpreter
    private fun mapFileToByteBuffer(f: File): ByteBuffer {
        val fis = FileInputStream(f)
        val fc: FileChannel = fis.channel
        val size = fc.size()
        val bb = fc.map(FileChannel.MapMode.READ_ONLY, 0, size)
        fc.close()
        fis.close()
        return bb.order(ByteOrder.nativeOrder())
    }

    /** Dump all .tflite files in app files/models directory. */
    suspend fun dumpModelsInAppModelsDir(context: Context) = withContext(Dispatchers.IO) {
        try {
            val modelsDir = File(context.filesDir, "models")
            if (!modelsDir.exists() || !modelsDir.isDirectory) {
                FileLogger.d(context, TAG, "models dir not found at ${modelsDir.absolutePath}")
                return@withContext
            }
            val outFile = File(context.filesDir, "tflite_signatures.txt")
            val fw = FileWriter(outFile, false)
            fw.appendLine("TFLite Signature Dump - ${System.currentTimeMillis()}")
            fw.appendLine("Found models in: ${modelsDir.absolutePath}")
            fw.appendLine("----")
            val modelFiles = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".tflite", true) } ?: emptyArray()
            if (modelFiles.isEmpty()) {
                fw.appendLine("No .tflite files found.")
                fw.flush(); fw.close()
                FileLogger.d(context, TAG, "No .tflite files found in ${modelsDir.absolutePath}")
                return@withContext
            }

            for (mf in modelFiles) {
                fw.appendLine("Model: ${mf.name}")
                fw.appendLine("Path: ${mf.absolutePath}")
                fw.appendLine("Size (bytes): ${mf.length()}")
                fw.appendLine("----")
                try {
                    val bb = mapFileToByteBuffer(mf)
                    val interpreter = Interpreter(bb)
                    dumpInterpreterToWriter(context, interpreter, mf.name, fw)
                    interpreter.close()
                } catch (e: Exception) {
                    fw.appendLine("ERROR loading model: ${e.message}")
                    FileLogger.e(context, TAG, "Error loading ${mf.name}: ${e.message}")
                }
                fw.appendLine("====")
            }
            fw.flush(); fw.close()
            FileLogger.d(context, TAG, "Wrote tflite signatures to ${outFile.absolutePath}")
        } catch (e: Exception) {
            FileLogger.e(context, TAG, "dumpModelsInAppModelsDir failed: ${e.message}")
        }
    }

    /** Dump a single Interpreter to a file & log. */
    suspend fun dumpInterpreter(context: Context, interpreter: Interpreter, label: String) = withContext(Dispatchers.IO) {
        val outFile = File(context.filesDir, "tflite_signatures.txt")
        FileWriter(outFile, true).use { fw -> // append
            fw.appendLine("Interpreter dump: $label @ ${System.currentTimeMillis()}")
            dumpInterpreterToWriter(context, interpreter, label, fw)
            fw.appendLine("----")
            fw.flush()
        }
        FileLogger.d(context, TAG, "dumpInterpreter($label) appended to ${outFile.absolutePath}")
    }

    // internal helper to write interpreter details
    private fun dumpInterpreterToWriter(context: Context, interpreter: Interpreter, label: String, fw: FileWriter) {
        try {
            val inCount = interpreter.inputTensorCount
            val outCount = interpreter.outputTensorCount
            fw.appendLine("Interpreter Label: $label")
            fw.appendLine("Inputs: count=$inCount")
            for (i in 0 until inCount) {
                try {
                    val t = interpreter.getInputTensor(i)
                    val n = safeName(t.name())
                    val s = t.shape().joinToString(prefix = "[", postfix = "]", separator = ",")
                    val dt = safeDataType(t.dataType())
                    fw.appendLine("  input[$i]: name=$n, shape=$s, dtype=$dt")
                } catch (e: Exception) {
                    fw.appendLine("  input[$i]: ERROR reading tensor: ${e.message}")
                }
            }
            fw.appendLine("Outputs: count=$outCount")
            for (i in 0 until outCount) {
                try {
                    val t = interpreter.getOutputTensor(i)
                    val n = safeName(t.name())
                    val s = t.shape().joinToString(prefix = "[", postfix = "]", separator = ",")
                    val dt = safeDataType(t.dataType())
                    fw.appendLine("  output[$i]: name=$n, shape=$s, dtype=$dt")
                } catch (e: Exception) {
                    fw.appendLine("  output[$i]: ERROR reading tensor: ${e.message}")
                }
            }
            // Try to read signature runner info if any (some models embed signatures)
            try {
                val sigNames = interpreter.signatureKeys
                if (sigNames != null && sigNames.isNotEmpty()) {
                    fw.appendLine("Signature keys present: ${sigNames.joinToString()}")
                }
            } catch (_: Exception) { }
            fw.appendLine("") // newline
            fw.flush()
        } catch (e: Exception) {
            fw.appendLine("dumpInterpreterToWriter failed: ${e.message}")
        }
    }

    private fun safeName(n: String?): String {
        return n ?: "<null>"
    }

    private fun safeDataType(dt: DataType?): String {
        return dt?.name ?: "<unknown>"
    }
}
