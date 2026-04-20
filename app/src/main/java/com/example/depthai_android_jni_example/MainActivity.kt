package com.example.depthai_android_jni_example

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.example.depthai_android_jni_example.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("depthai_android_jni_example")
        }

        private const val YOLOV3_MODEL_PATH = "yolo-v3-tiny-tf.blob"
        private const val YOLOV4_MODEL_PATH = "yolov4_tiny_coco_416x416_6shave.blob"
        private const val YOLOV5_MODEL_PATH = "yolov5s_416_6shave.blob"
        private const val MOBILENET_MODEL_PATH = "mobilenet-ssd.blob"

        private const val RGB_WIDTH = 416
        private const val RGB_HEIGHT = 416
        private const val DISPARITY_WIDTH = 640
        private const val DISPARITY_HEIGHT = 400
        private const val FRAME_PERIOD = 30L
    }

    private lateinit var rgbImageView: ImageView
    private lateinit var depthImageView: ImageView
    private lateinit var rgbImage: Bitmap
    private lateinit var depthImage: Bitmap

    private var running = true
    private var firstTime = true

    private val handler = Handler(Looper.getMainLooper())

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (running) {
                if (firstTime) {
                    startDevice(YOLOV5_MODEL_PATH, RGB_WIDTH, RGB_HEIGHT)
                    firstTime = false
                }

                val rgb = imageFromJNI()
                if (rgb != null && rgb.size > 0) {
                    rgbImage.setPixels(rgb, 0, RGB_WIDTH, 0, 0, RGB_WIDTH, RGB_HEIGHT)
                    rgbImageView.setImageBitmap(rgbImage)
                }

                val detectionsImage = detectionImageFromJNI()
                if (detectionsImage != null && detectionsImage.size > 0) {
                    rgbImage.setPixels(detectionsImage, 0, RGB_WIDTH, 0, 0, RGB_WIDTH, RGB_HEIGHT)
                    rgbImageView.setImageBitmap(rgbImage)
                }

                val depth = depthFromJNI()
                if (depth != null && depth.size > 0) {
                    depthImage.setPixels(depth, 0, DISPARITY_WIDTH, 0, 0, DISPARITY_WIDTH, DISPARITY_HEIGHT)
                    depthImageView.setImageBitmap(depthImage)
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

        rgbImageView = binding.rgbImageView
        depthImageView = binding.depthImageView

        rgbImage = Bitmap.createBitmap(RGB_WIDTH, RGB_HEIGHT, Bitmap.Config.ARGB_8888)
        depthImage = Bitmap.createBitmap(DISPARITY_WIDTH, DISPARITY_HEIGHT, Bitmap.Config.ARGB_8888)

        if (savedInstanceState != null) {
            running = savedInstanceState.getBoolean("running", true)
            firstTime = savedInstanceState.getBoolean("firstTime", true)
        }

        frameRunnable.run()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        firstTime = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("running", running)
        outState.putBoolean("firstTime", firstTime)
    }

    fun getAssetManager(): AssetManager = assets

    external fun startDevice(modelPath: String, rgbWidth: Int, rgbHeight: Int)
    external fun imageFromJNI(): IntArray?
    external fun detectionImageFromJNI(): IntArray?
    external fun depthFromJNI(): IntArray?
}