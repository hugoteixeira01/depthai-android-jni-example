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
        private const val STEREO_WIDTH = 640
        private const val STEREO_HEIGHT = 400
        private const val FRAME_PERIOD = 30L

        private val MODEL_LABEL_SEPARATOR = Regex("[_-]+")

        enum class TaskType(val displayName: String) {
            DETECTION("Detection"),
            SEGMENTATION("Segmentation"),
            CLASSIFICATION("Classification"),
            POSE_ESTIMATION("Pose Estimation")
        }

        fun taskTypeFromModelName(modelName: String): TaskType {
            return when {
                modelName.contains(Regex("yolo|mobilenet|ssd", RegexOption.IGNORE_CASE)) -> TaskType.DETECTION
                modelName.contains(Regex("fcn|unet|segmentation", RegexOption.IGNORE_CASE)) -> TaskType.SEGMENTATION
                modelName.contains(Regex("resnet|inception|classification", RegexOption.IGNORE_CASE)) -> TaskType.CLASSIFICATION
                modelName.contains(Regex("pose|openpose|keypoint", RegexOption.IGNORE_CASE)) -> TaskType.POSE_ESTIMATION
                else -> TaskType.DETECTION // Default to detection if pattern unclear
            }
        }
    }

    private data class ModelOption(val label: String, val assetPath: String)

    private data class StreamOption(
        val label: String,
        val width: Int,
        val height: Int,
        val bitmap: Bitmap,
        val fetchFrame: () -> IntArray?
    )

    private var modelOptions: List<ModelOption> = emptyList()
    private var streamOptions: List<StreamOption> = emptyList()
    private var filteredModelOptions: List<ModelOption> = emptyList()

    private lateinit var previewImageView: ImageView
    private lateinit var leftArrow: ImageButton
    private lateinit var rightArrow: ImageButton
    private lateinit var streamLabel: TextView
    private lateinit var taskSpinner: Spinner
    private lateinit var modelSpinner: Spinner

    private lateinit var rgbBitmap: Bitmap
    private lateinit var rgbOverlayBitmap: Bitmap
    private lateinit var disparityBitmap: Bitmap
    private lateinit var rectifiedLeftBitmap: Bitmap
    private lateinit var rectifiedRightBitmap: Bitmap
    private lateinit var confidenceBitmap: Bitmap

    private var running = true
    private var cameraConnected = false
    private var selectedModelIndex = AdapterView.INVALID_POSITION
    private var selectedStreamIndex = 0
    private var selectedTaskIndex = 0

    private val handler = Handler(Looper.getMainLooper())

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (running) {
                if (cameraConnected) {
                    val currentStream = streamOptions.getOrNull(selectedStreamIndex)
                    val frame = currentStream?.fetchFrame?.invoke()
                    if (currentStream != null && frame != null && frame.isNotEmpty()) {
                        currentStream.bitmap.setPixels(frame, 0, currentStream.width, 0, 0, currentStream.width, currentStream.height)
                        previewImageView.setImageBitmap(currentStream.bitmap)
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(binding.root)

        previewImageView = binding.previewImageView
        leftArrow = binding.leftArrow
        rightArrow = binding.rightArrow
        streamLabel = binding.streamLabel

        rgbBitmap = Bitmap.createBitmap(RGB_WIDTH, RGB_HEIGHT, Bitmap.Config.ARGB_8888)
        rgbOverlayBitmap = Bitmap.createBitmap(RGB_WIDTH, RGB_HEIGHT, Bitmap.Config.ARGB_8888)
        disparityBitmap = Bitmap.createBitmap(STEREO_WIDTH, STEREO_HEIGHT, Bitmap.Config.ARGB_8888)
        rectifiedLeftBitmap = Bitmap.createBitmap(STEREO_WIDTH, STEREO_HEIGHT, Bitmap.Config.ARGB_8888)
        rectifiedRightBitmap = Bitmap.createBitmap(STEREO_WIDTH, STEREO_HEIGHT, Bitmap.Config.ARGB_8888)
        confidenceBitmap = Bitmap.createBitmap(STEREO_WIDTH, STEREO_HEIGHT, Bitmap.Config.ARGB_8888)

        if (savedInstanceState != null) {
            running = savedInstanceState.getBoolean("running", true)
            cameraConnected = savedInstanceState.getBoolean("cameraConnected", false)
            selectedModelIndex = savedInstanceState.getInt("selectedModelIndex", AdapterView.INVALID_POSITION)
            selectedStreamIndex = savedInstanceState.getInt("selectedStreamIndex", 0)
            selectedTaskIndex = savedInstanceState.getInt("selectedTaskIndex", 0)
        }

        val savedSelectedModelPath = savedInstanceState?.getString("selectedModelPath")
        modelOptions = findModelOptions()
        
        taskSpinner = binding.taskSelector
        modelSpinner = binding.modelSelector
        
        // Setup task spinner
        val taskAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, TaskType.values().map { it.displayName })
        taskAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        taskSpinner.adapter = taskAdapter
        taskSpinner.setSelection(selectedTaskIndex, false)
        
        updateFilteredModels()
        
        selectedModelIndex = when {
            filteredModelOptions.isEmpty() -> AdapterView.INVALID_POSITION
            savedSelectedModelPath != null -> {
                val savedModelIndex = filteredModelOptions.indexOfFirst { it.assetPath == savedSelectedModelPath }
                if (savedModelIndex != AdapterView.INVALID_POSITION) savedModelIndex else 0
            }
            selectedModelIndex in filteredModelOptions.indices -> selectedModelIndex
            else -> 0
        }

        // Task spinner listener
        taskSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedTaskIndex = position
                updateFilteredModels()
                updateModelSpinner()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Model spinner setup
        modelSpinner.let { spinner: Spinner ->
            updateModelSpinner()
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (position in filteredModelOptions.indices) {
                        selectedModelIndex = position
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    // Keep the previous selection.
                }
            }
        }

        leftArrow.setOnClickListener {
            if (selectedStreamIndex > 0) {
                selectedStreamIndex--
                updateCarouselControls()
            }
        }

        rightArrow.setOnClickListener {
            if (selectedStreamIndex < streamOptions.lastIndex) {
                selectedStreamIndex++
                updateCarouselControls()
            }
        }

        val connectButton = binding.connectCameraButton
        connectButton?.let { button: Button ->
            button.setOnClickListener {
                if (cameraConnected) {
                    disconnectCamera()
                } else {
                    val selectedModelPath = filteredModelOptions.getOrNull(selectedModelIndex)?.assetPath
                    if (selectedModelPath != null) {
                        connectCamera(selectedModelPath)
                    }
                }

                updateControls(button, modelSpinner)
                updateCarouselControls()
            }
        }

        if (cameraConnected) {
            val selectedModelPath = filteredModelOptions.getOrNull(selectedModelIndex)?.assetPath
            if (selectedModelPath != null) {
                connectCamera(selectedModelPath)
            } else {
                cameraConnected = false
                streamOptions = createStreamOptions(false)
                selectedStreamIndex = selectedStreamIndex.coerceIn(0, streamOptions.lastIndex)
            }
        } else {
            streamOptions = createStreamOptions(false)
            selectedStreamIndex = selectedStreamIndex.coerceIn(0, streamOptions.lastIndex)
        }

        connectButton?.let { updateControls(it, modelSpinner) }
        updateCarouselControls()

        frameRunnable.run()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (cameraConnected) {
            disconnectCamera()
        }
        running = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("running", running)
        outState.putBoolean("cameraConnected", cameraConnected)
        outState.putInt("selectedModelIndex", selectedModelIndex)
        outState.putInt("selectedStreamIndex", selectedStreamIndex)
        outState.putInt("selectedTaskIndex", selectedTaskIndex)
        filteredModelOptions.getOrNull(selectedModelIndex)?.assetPath?.let { selectedModelPath ->
            outState.putString("selectedModelPath", selectedModelPath)
        }
    }

    fun getAssetManager(): AssetManager = assets

    private fun connectCamera(modelPath: String) {
        startDevice(modelPath, RGB_WIDTH, RGB_HEIGHT)
        cameraConnected = true
        streamOptions = createStreamOptions(hasStereoFromJNI())
        selectedStreamIndex = selectedStreamIndex.coerceIn(0, streamOptions.lastIndex)
    }

    private fun disconnectCamera() {
        stopDevice()
        cameraConnected = false
        streamOptions = createStreamOptions(false)
        selectedStreamIndex = 0
    }

    private fun updateControls(button: Button, modelSpinner: Spinner?) {
        if (cameraConnected) {
            button.setText("Disconnect Camera")
            button.isEnabled = true
            taskSpinner.isEnabled = false
            modelSpinner?.isEnabled = false
        } else {
            val hasModelOptions = filteredModelOptions.isNotEmpty()
            button.setText(if (hasModelOptions) "Connect Camera" else "No model blobs found")
            button.isEnabled = hasModelOptions
            taskSpinner.isEnabled = true
            modelSpinner?.isEnabled = hasModelOptions
        }
    }

    private fun updateFilteredModels() {
        val selectedTask = TaskType.values().getOrNull(selectedTaskIndex) ?: TaskType.DETECTION
        filteredModelOptions = modelOptions.filter { modelOption ->
            val modelName = modelOption.label
            taskTypeFromModelName(modelName) == selectedTask
        }
    }

    private fun updateModelSpinner() {
        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            filteredModelOptions.map { it.label }
        )
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = spinnerAdapter
        
        selectedModelIndex = when {
            filteredModelOptions.isEmpty() -> AdapterView.INVALID_POSITION
            selectedModelIndex in filteredModelOptions.indices -> selectedModelIndex
            else -> 0
        }
        
        if (selectedModelIndex != AdapterView.INVALID_POSITION) {
            modelSpinner.setSelection(selectedModelIndex, false)
        }
    }

    private fun updateCarouselControls() {
        val currentStream = streamOptions.getOrNull(selectedStreamIndex)
        if (currentStream != null) {
            streamLabel.text = "${currentStream.label} ${selectedStreamIndex + 1}/${streamOptions.size}"
        } else {
            streamLabel.text = "Stream"
        }

        leftArrow.isEnabled = cameraConnected && selectedStreamIndex > 0
        rightArrow.isEnabled = cameraConnected && selectedStreamIndex < streamOptions.lastIndex
    }

    private fun createStreamOptions(stereoSupported: Boolean): List<StreamOption> {
        return buildList {
            add(StreamOption("RGB", RGB_WIDTH, RGB_HEIGHT, rgbBitmap) { imageFromJNI() })
            add(StreamOption("Detections", RGB_WIDTH, RGB_HEIGHT, rgbOverlayBitmap) { captureRgbOverlayFrame() })

            if (stereoSupported) {
                add(StreamOption("Disparity", STEREO_WIDTH, STEREO_HEIGHT, disparityBitmap) { depthFromJNI() })
                add(StreamOption("Rectified Left", STEREO_WIDTH, STEREO_HEIGHT, rectifiedLeftBitmap) { rectifiedLeftFromJNI() })
                add(StreamOption("Rectified Right", STEREO_WIDTH, STEREO_HEIGHT, rectifiedRightBitmap) { rectifiedRightFromJNI() })
                add(StreamOption("Confidence Map", STEREO_WIDTH, STEREO_HEIGHT, confidenceBitmap) { confidenceMapFromJNI() })
            }
        }
    }

    private fun captureRgbOverlayFrame(): IntArray? {
        val rgb = imageFromJNI() ?: return null
        val overlay = detectionImageFromJNI()
        return overlay?.takeIf { it.isNotEmpty() } ?: rgb
    }

    private fun findModelOptions(): List<ModelOption> {
        return try {
            findBlobAssetPaths("")
                .map { assetPath -> ModelOption(formatModelLabel(assetPath), assetPath) }
                .sortedBy { it.assetPath.toLowerCase(Locale.US) }
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
    external fun hasStereoFromJNI(): Boolean
    external fun imageFromJNI(): IntArray?
    external fun detectionImageFromJNI(): IntArray?
    external fun depthFromJNI(): IntArray?
    external fun rectifiedLeftFromJNI(): IntArray?
    external fun rectifiedRightFromJNI(): IntArray?
    external fun confidenceMapFromJNI(): IntArray?
}
