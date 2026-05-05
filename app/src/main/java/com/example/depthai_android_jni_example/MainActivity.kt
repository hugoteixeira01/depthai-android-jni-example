package com.example.depthai_android_jni_example

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.depthai_android_jni_example.databinding.ActivityMainBinding
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("depthai_android_jni_example")
        }

        private const val BLOB_EXTENSION = ".blob"
        private const val RGB_WIDTH = 416
        private const val RGB_HEIGHT = 416
        private const val DISPARITY_WIDTH = 640
        private const val DISPARITY_HEIGHT = 400
        private const val FRAME_PERIOD = 30L

        private val MODEL_LABEL_SEPARATOR = Regex("[_-]+")

        private const val STREAM_RGB = 0
        private const val STREAM_DEPTH = 1
        private const val STREAM_COUNT = 2
    }

    private data class ModelOption(val label: String, val assetPath: String)

    private var modelOptions: List<ModelOption> = emptyList()

    private lateinit var previewImageView: ImageView
    private lateinit var leftArrow: ImageButton
    private lateinit var rightArrow: ImageButton
    private lateinit var streamLabel: TextView

    private lateinit var rgbImage: Bitmap
    private lateinit var depthImage: Bitmap

    private var running = true
    private var cameraConnected = false
    private var selectedModelIndex = AdapterView.INVALID_POSITION
    private var selectedStreamIndex = STREAM_RGB

    private val handler = Handler(Looper.getMainLooper())

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (running) {
                if (cameraConnected) {
                    val rgb = imageFromJNI()
                    val detectionsImage = detectionImageFromJNI()
                    val depth = depthFromJNI()

                    if (selectedStreamIndex == STREAM_RGB) {
                        if (rgb != null && rgb.isNotEmpty()) {
                            rgbImage.setPixels(rgb, 0, RGB_WIDTH, 0, 0, RGB_WIDTH, RGB_HEIGHT)
                        }
                        if (detectionsImage != null && detectionsImage.isNotEmpty()) {
                            rgbImage.setPixels(detectionsImage, 0, RGB_WIDTH, 0, 0, RGB_WIDTH, RGB_HEIGHT)
                        }
                        previewImageView.setImageBitmap(rgbImage)
                    } else if (selectedStreamIndex == STREAM_DEPTH) {
                        if (depth != null && depth.isNotEmpty()) {
                            depthImage.setPixels(depth, 0, DISPARITY_WIDTH, 0, 0, DISPARITY_WIDTH, DISPARITY_HEIGHT)
                        }
                        previewImageView.setImageBitmap(depthImage)
                    }
                }

                handler.postDelayed(this, FRAME_PERIOD)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityMainBinding.inflate(layoutInflater)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(binding.root)

        previewImageView = binding.previewImageView
        leftArrow = binding.leftArrow
        rightArrow = binding.rightArrow
        streamLabel = binding.streamLabel

        rgbImage = Bitmap.createBitmap(RGB_WIDTH, RGB_HEIGHT, Bitmap.Config.ARGB_8888)
        depthImage = Bitmap.createBitmap(DISPARITY_WIDTH, DISPARITY_HEIGHT, Bitmap.Config.ARGB_8888)

        if (savedInstanceState != null) {
            running = savedInstanceState.getBoolean("running", true)
            cameraConnected = savedInstanceState.getBoolean("cameraConnected", false)
            selectedModelIndex = savedInstanceState.getInt("selectedModelIndex", AdapterView.INVALID_POSITION)
            selectedStreamIndex = savedInstanceState.getInt("selectedStreamIndex", STREAM_RGB)
        }

        val savedSelectedModelPath = savedInstanceState?.getString("selectedModelPath")
        modelOptions = findModelOptions()
        selectedModelIndex = when {
            modelOptions.isEmpty() -> AdapterView.INVALID_POSITION
            savedSelectedModelPath != null -> {
                val savedModelIndex = modelOptions.indexOfFirst { it.assetPath == savedSelectedModelPath }
                if (savedModelIndex != AdapterView.INVALID_POSITION) savedModelIndex else 0
            }
            selectedModelIndex in modelOptions.indices -> selectedModelIndex
            else -> 0
        }

        val modelSpinner = binding.modelSelector
        modelSpinner?.let { spinner: Spinner ->
            val spinnerAdapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                modelOptions.map { it.label }
            )
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = spinnerAdapter
            if (selectedModelIndex != AdapterView.INVALID_POSITION) {
                spinner.setSelection(selectedModelIndex, false)
            }
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (position in modelOptions.indices) {
                        selectedModelIndex = position
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    // Keep previously selected model index.
                }
            }
        }

        // Arrow handlers
        leftArrow.setOnClickListener {
            if (selectedStreamIndex > 0) {
                selectedStreamIndex--
                updateCarouselControls()
            }
        }
        rightArrow.setOnClickListener {
            if (selectedStreamIndex < STREAM_COUNT - 1) {
                selectedStreamIndex++
                updateCarouselControls()
            }
        }

        binding.connectCameraButton?.let { button: Button ->
            button.setOnClickListener {
                if (cameraConnected) {
                    stopDevice()
                    cameraConnected = false
                } else {
                    val selectedModelPath = modelOptions.getOrNull(selectedModelIndex)?.assetPath
                    if (selectedModelPath != null) {
                        startDevice(selectedModelPath, RGB_WIDTH, RGB_HEIGHT)
                        cameraConnected = true
                    }
                }

                updateControls(button, modelSpinner)
                updateCarouselControls()
            }

            updateControls(button, modelSpinner)
            updateCarouselControls()
        }

        frameRunnable.run()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (cameraConnected) {
            stopDevice()
            cameraConnected = false
        }
        running = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("running", running)
        outState.putBoolean("cameraConnected", cameraConnected)
        outState.putInt("selectedModelIndex", selectedModelIndex)
        outState.putInt("selectedStreamIndex", selectedStreamIndex)
        modelOptions.getOrNull(selectedModelIndex)?.assetPath?.let { selectedModelPath ->
            outState.putString("selectedModelPath", selectedModelPath)
        }
    }

    fun getAssetManager(): AssetManager = assets

    private fun updateControls(button: Button, modelSpinner: Spinner?) {
        if (cameraConnected) {
            button.setText("Disconnect Camera")
            button.isEnabled = true
            modelSpinner?.isEnabled = false
            leftArrow.isEnabled = true
            rightArrow.isEnabled = true
        } else {
            val hasModelOptions = modelOptions.isNotEmpty()
            button.setText(if (hasModelOptions) "Connect Camera" else "No model blobs found")
            button.isEnabled = hasModelOptions
            modelSpinner?.isEnabled = hasModelOptions
            leftArrow.isEnabled = false
            rightArrow.isEnabled = false
        }
    }

    private fun updateCarouselControls() {
        val label = when (selectedStreamIndex) {
            STREAM_RGB -> "RGB 1/2"
            STREAM_DEPTH -> "Depth 2/2"
            else -> "Stream"
        }
        streamLabel.text = label
        leftArrow.isEnabled = (selectedStreamIndex > 0) && cameraConnected
        rightArrow.isEnabled = (selectedStreamIndex < STREAM_COUNT - 1) && cameraConnected
    }

    private fun findModelOptions(): List<ModelOption> {
        return try {
            findBlobAssetPaths("")
                .map { assetPath -> ModelOption(formatModelLabel(assetPath), assetPath) }
                .sortedBy { it.assetPath.lowercase(Locale.US) }
        } catch (exception: IOException) {
            emptyList()
        }
    }

    private fun findBlobAssetPaths(assetPath: String): List<String> {
        return assets.list(assetPath).orEmpty().flatMap { childName ->
            val childPath = if (assetPath.isEmpty()) childName else "$assetPath/$childName"
            val childAssets = assets.list(childPath).orEmpty()
            if (childAssets.isEmpty()) {
                if (childName.endsWith(BLOB_EXTENSION, ignoreCase = true)) listOf(childPath) else emptyList()
            } else {
                findBlobAssetPaths(childPath)
            }
        }
    }

    private fun formatModelLabel(assetPath: String): String {
        val fileName = assetPath.substringAfterLast('/')
        val modelName = if (fileName.endsWith(BLOB_EXTENSION, ignoreCase = true)) {
            fileName.dropLast(BLOB_EXTENSION.length)
        } else {
            fileName
        }
        val label = modelName.replace(MODEL_LABEL_SEPARATOR, " ").trim()
        return label.ifEmpty { fileName }
    }

    external fun startDevice(modelPath: String, rgbWidth: Int, rgbHeight: Int)
    external fun stopDevice()
    external fun imageFromJNI(): IntArray?
    external fun detectionImageFromJNI(): IntArray?
    external fun depthFromJNI(): IntArray?
}
