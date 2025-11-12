// Copyright (C) 2021  ArchieMeng <archiemeng@protonmail.com>
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package pro.archiemeng.waifu2x

import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pro.archiemeng.waifu2x.utils.Processor
import java.io.File
import java.io.FileNotFoundException
import java.lang.Integer.min

class MainActivity : AppCompatActivity() {

    private var imageView: ImageView? = null
    private var bitmap: Bitmap? = null
    private var yourSelectedImage: Bitmap? = null
    private var numThreads: Int = 1
    private var outputFile: File? = null

    private val upscaler = Waifu2x()
    private val processJob = Job()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + processJob)

    companion object {
        private const val SELECT_IMAGE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        imageView = findViewById(R.id.imageView)

        val buttonImage = findViewById<Button>(R.id.buttonImage)
        val buttonDetect = findViewById<Button>(R.id.buttonDetect)
        val buttonDetectGPU = findViewById<Button>(R.id.buttonDetectGPU)
        val buttonDownload = findViewById<Button>(R.id.buttonDownloadImage)

        buttonImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK)
            intent.type = "image/*"
            startActivityForResult(intent, SELECT_IMAGE)
        }

        buttonDetect.setOnClickListener {
            yourSelectedImage?.let {
                coroutineScope.launch {
                    numThreads = 8
                    processBitmapWithCanvas(upscaler, it, useGPU = false)
                }
            }
        }

        buttonDetectGPU.setOnClickListener {
            yourSelectedImage?.let {
                coroutineScope.launch {
                    numThreads = 1
                    processBitmapWithCanvas(upscaler, it, useGPU = true)
                }
            }
        }

        buttonDownload.setOnClickListener {
            coroutineScope.launch(Dispatchers.IO) {
                if (outputFile == null || !outputFile!!.exists()) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Chưa có ảnh để lưu", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                try {
                    val fileName = "enhanced_${System.currentTimeMillis()}.png"

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // 🪄 Lưu vào MediaStore (Android 10+)
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Waifu2x")
                            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                        }

                        val resolver = contentResolver
                        val uri = resolver.insert(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            values
                        )

                        uri?.let {
                            resolver.openOutputStream(it)?.use { outputStream ->
                                outputFile!!.inputStream().use { input ->
                                    input.copyTo(outputStream)
                                }
                            }

                            values.clear()
                            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(uri, values, null, null)

                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Đã lưu vào Pictures/Waifu2x",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } ?: run {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Không thể lưu ảnh!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        // 🧩 Android 9 trở xuống: ghi trực tiếp ra /Pictures/Waifu2x
                        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        val waifuDir = File(picturesDir, "Waifu2x")
                        if (!waifuDir.exists()) waifuDir.mkdirs()

                        val destFile = File(waifuDir, fileName)
                        outputFile!!.copyTo(destFile, overwrite = true)

                        val uri = Uri.fromFile(destFile)
                        sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))

                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Đã lưu: ${destFile.absolutePath}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Lưu ảnh thất bại!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }


    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode == SELECT_IMAGE) {
            val selectedImage = data?.data
            selectedImage?.let {
                try {
                    bitmap = decodeUri(it)
                    yourSelectedImage = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
                    runOnUiThread { imageView?.setImageBitmap(bitmap) }
                } catch (e: FileNotFoundException) {
                    Log.e("MainActivity", "FileNotFoundException")
                }
            }
        }
    }

    /**
     * Progressive upscale bằng Canvas để vẽ trực tiếp
     */
    private fun processBitmapWithCanvas(upscaler: Processor, bitmapInput: Bitmap, useGPU: Boolean) {
        val startTime = System.currentTimeMillis()

        // Khởi tạo model
        upscaler.init(
            assets,
            useGPU,
            "models-upconv_7_photo",
            2,
            0,
            false,
            numThreads,
            128,
        )

        if (!upscaler.useGPU) {
            upscaler.tileSize = 256
        }

        // Bitmap đầu ra + canvas để vẽ trực tiếp
        val outputWidth = bitmapInput.width * upscaler.scale
        val outputHeight = bitmapInput.height * upscaler.scale

        val bitmapOutput = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmapOutput)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

        // Gán bitmap cho ImageView 1 lần duy nhất
        runOnUiThread { imageView?.setImageBitmap(bitmapOutput) }

        for (y in 0 until bitmapInput.height step upscaler.tileSize) {
            val tileHeight = min(bitmapInput.height - y, upscaler.tileSize)
            for (x in 0 until bitmapInput.width step upscaler.tileSize) {
                val tileWidth = min(bitmapInput.width - x, upscaler.tileSize)

                // Cắt tile nhỏ
                var tileBitmap = Bitmap.createBitmap(bitmapInput, x, y, tileWidth, tileHeight)
                // Xử lý upscale tile
                tileBitmap = upscaler.process(tileBitmap)

                // Tính vị trí cần vẽ tile
                val dstX = x * upscaler.scale
                val dstY = y * upscaler.scale

                // Vẽ trực tiếp lên canvas
                canvas.drawBitmap(tileBitmap, dstX.toFloat(), dstY.toFloat(), paint)

                // Cập nhật UI nhẹ nhàng
                runOnUiThread { imageView?.invalidate() }

                tileBitmap.recycle()
            }
        }

        // Sau khi xong, lưu ra file nếu cần
        val tempFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            File.createTempFile("upscaled_", ".webp")
        else
            File.createTempFile("upscaled_", ".png")

        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG

        bitmapOutput.compress(format, 100, tempFile.outputStream())
        outputFile = tempFile  // ✅ lưu lại để download sau


        val elapsed = System.currentTimeMillis() - startTime
        Log.d("Waifu2x", "Processing done in ${elapsed}ms, saved: ${tempFile.absolutePath}")
    }

    @Throws(FileNotFoundException::class)
    private fun decodeUri(selectedImage: Uri): Bitmap? {
        return BitmapFactory.decodeStream(contentResolver.openInputStream(selectedImage))
    }
}
