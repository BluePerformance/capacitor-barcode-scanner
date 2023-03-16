package com.getcapacitor.community.barcodescanner;

import static android.content.Context.MODE_PRIVATE;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;

import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;

import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResult;
import androidx.annotation.NonNull;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.Intents;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.journeyapps.barcodescanner.camera.CameraSettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONException;

@CapacitorPlugin(permissions = {@Permission(strings = {Manifest.permission.CAMERA}, alias = BarcodeScanner.PERMISSION_ALIAS_CAMERA)})
public class BarcodeScanner extends Plugin implements BarcodeCallback {

  public static final String PERMISSION_ALIAS_CAMERA = "camera";

  private BarcodeView mBarcodeView;

  // private int currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;

  private CameraDevice mCameraDevice;

  private boolean isScanning = false;
  private boolean shouldRunScan = false;
  private boolean didRunCameraSetup = false;
  private boolean didRunCameraPrepare = false;
  private boolean isBackgroundHidden = false;
  private boolean isTorchOn = false;
  private boolean scanningPaused = false;
  private String lastScanResult = null;

  // declare a map constant for allowed barcode formats
  private static final Map<String, BarcodeFormat> supportedFormats = supportedFormats();

  private static Map<String, BarcodeFormat> supportedFormats() {
    Map<String, BarcodeFormat> map = new HashMap<>();
    // 1D Product
    map.put("UPC_A", BarcodeFormat.UPC_A);
    map.put("UPC_E", BarcodeFormat.UPC_E);
    map.put("UPC_EAN_EXTENSION", BarcodeFormat.UPC_EAN_EXTENSION);
    map.put("EAN_8", BarcodeFormat.EAN_8);
    map.put("EAN_13", BarcodeFormat.EAN_13);
    // 1D Industrial
    map.put("CODE_39", BarcodeFormat.CODE_39);
    map.put("CODE_93", BarcodeFormat.CODE_93);
    map.put("CODE_128", BarcodeFormat.CODE_128);
    map.put("CODABAR", BarcodeFormat.CODABAR);
    map.put("ITF", BarcodeFormat.ITF);
    // 2D
    map.put("AZTEC", BarcodeFormat.AZTEC);
    map.put("DATA_MATRIX", BarcodeFormat.DATA_MATRIX);
    map.put("MAXICODE", BarcodeFormat.MAXICODE);
    map.put("PDF_417", BarcodeFormat.PDF_417);
    map.put("QR_CODE", BarcodeFormat.QR_CODE);
    map.put("RSS_14", BarcodeFormat.RSS_14);
    map.put("RSS_EXPANDED", BarcodeFormat.RSS_EXPANDED);
    return Collections.unmodifiableMap(map);
  }

  private boolean hasCamera() {
    // @TODO(): check: https://stackoverflow.com/a/57974578/8634342
    if (getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
      return true;
    } else {
      return false;
    }
  }

  private void setupCamera(String cameraDirection) {
    // @TODO(): add support for switching cameras while scanning is running

    getActivity()
      .runOnUiThread(
        () -> {
          // Create BarcodeView
          mBarcodeView = new BarcodeView(getActivity());

          // Configure the camera (front/back)
          CameraSettings settings = new CameraSettings();
          settings.setRequestedCameraId(
            "front".equals(cameraDirection) ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK
          );
          settings.setContinuousFocusEnabled(true);
          mBarcodeView.setCameraSettings(settings);

          FrameLayout.LayoutParams cameraPreviewParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
          );

          // Set BarcodeView as sibling View of WebView
          ((ViewGroup) bridge.getWebView().getParent()).addView(mBarcodeView, cameraPreviewParams);

          // Bring the WebView in front of the BarcodeView
          // This allows us to completely style the BarcodeView in HTML/CSS
          bridge.getWebView().bringToFront();

          mBarcodeView.resume();
        }
      );

    didRunCameraSetup = true;
  }

  private void dismantleCamera() {
    // opposite of setupCamera

    getActivity()
      .runOnUiThread(
        () -> {
          if (mBarcodeView != null) {
            mBarcodeView.pause();
            mBarcodeView.stopDecoding();
            ((ViewGroup) bridge.getWebView().getParent()).removeView(mBarcodeView);
            mBarcodeView = null;
          }
        }
      );

    isScanning = false;
    didRunCameraSetup = false;
    didRunCameraPrepare = false;

    // If a call is saved and a scan will not run, free the saved call
    if (getSavedCall() != null && !shouldRunScan) {
      freeSavedCall();
    }
  }

  private void _prepare(PluginCall call) {
    // undo previous setup
    // because it may be prepared with a different config
    dismantleCamera();

    // setup camera with new config
    setupCamera(call.getString("cameraDirection", "back"));

    // indicate this method was run
    didRunCameraPrepare = true;

    if (shouldRunScan) {
      scan();
    }
  }

  private void destroy() {
    showBackground();
    dismantleCamera();
    this.setTorch(false);
  }

  private void setTorch(boolean b) {
    if (mBarcodeView != null) {
      mBarcodeView.setTorch(b);
    }
  }


  private void configureCamera() {
    getActivity()
      .runOnUiThread(
        () -> {
          PluginCall call = getSavedCall();

          if (call == null || mBarcodeView == null) {
            Log.d("scanner", "Something went wrong with configuring the BarcodeScanner.");
            return;
          }

          DefaultDecoderFactory defaultDecoderFactory = new DefaultDecoderFactory(null, null, null, Intents.Scan.MIXED_SCAN);

          if (call.hasOption("targetedFormats")) {
            JSArray targetedFormats = call.getArray("targetedFormats");
            ArrayList<BarcodeFormat> formatList = new ArrayList<>();

            if (targetedFormats != null && targetedFormats.length() > 0) {
              for (int i = 0; i < targetedFormats.length(); i++) {
                try {
                  String targetedFormat = targetedFormats.getString(i);
                  BarcodeFormat targetedBarcodeFormat = supportedFormats.get(targetedFormat);
                  if (targetedBarcodeFormat != null) {
                    formatList.add(targetedBarcodeFormat);
                  }
                } catch (JSONException e) {
                  e.printStackTrace();
                }
              }
            }

            if (formatList.size() > 0) {
              defaultDecoderFactory = new DefaultDecoderFactory(formatList, null, null, Intents.Scan.MIXED_SCAN);
            } else {
              Log.d("scanner", "The property targetedFormats was not set correctly.");
            }
          }

          mBarcodeView.setDecoderFactory(defaultDecoderFactory);
        }
      );
  }

  private void scan() {
    if (!didRunCameraPrepare) {
      if (hasCamera()) {
        if (!hasPermission(Manifest.permission.CAMERA)) {
          Log.d("scanner", "No permission to use camera. Did you request it yet?");
        } else {
          shouldRunScan = true;
          _prepare(getSavedCall());
        }
      }
    } else {
      didRunCameraPrepare = false;

      shouldRunScan = false;

      configureCamera();

      final BarcodeCallback b = this;
      getActivity()
        .runOnUiThread(
          () -> {
            if (mBarcodeView != null) {
              PluginCall call = getSavedCall();
              if (call != null && call.isKeptAlive()) {
                mBarcodeView.decodeContinuous(b);
              } else {
                mBarcodeView.decodeSingle(b);
              }
            }
          }
        );

      hideBackground();

      isScanning = true;
    }
  }

  private void hideBackground() {
    getActivity()
      .runOnUiThread(
        () -> {
          bridge.getWebView().setBackgroundColor(Color.TRANSPARENT);
          bridge.getWebView().loadUrl("javascript:document.documentElement.style.backgroundColor = 'transparent';void(0);");
          isBackgroundHidden = true;
        }
      );
  }

  private void showBackground() {
    getActivity()
      .runOnUiThread(
        () -> {
          bridge.getWebView().setBackgroundColor(Color.WHITE);
          bridge.getWebView().loadUrl("javascript:document.documentElement.style.backgroundColor = '';void(0);");
          isBackgroundHidden = false;
        }
      );
  }

  @Override
  public void barcodeResult(BarcodeResult barcodeResult) {
    JSObject jsObject = new JSObject();

    if (barcodeResult.getText() != null) {
      jsObject.put("hasContent", true);
      jsObject.put("content", barcodeResult.getText());
      jsObject.put("format", barcodeResult.getBarcodeFormat().name());
    } else {
      jsObject.put("hasContent", false);
    }

    PluginCall call = getSavedCall();

    if (call != null) {
      if (call.isKeptAlive()) {
        if (!scanningPaused && barcodeResult.getText() != null && !barcodeResult.getText().equals(lastScanResult)) {
          lastScanResult = barcodeResult.getText();
          call.resolve(jsObject);
        }
      } else {
        call.resolve(jsObject);
        destroy();
      }
    } else {
      destroy();
    }
  }

  @Override
  public void handleOnPause() {
    if (mBarcodeView != null) {
      mBarcodeView.pause();
    }
  }

  @Override
  public void handleOnResume() {
    if (mBarcodeView != null) {
      mBarcodeView.resume();
    }
  }

  @Override
  public void possibleResultPoints(List<ResultPoint> resultPoints) {
  }

  @PluginMethod
  public void prepare(PluginCall call) {
    _prepare(call);
    call.resolve();
  }

  @PluginMethod
  public void hideBackground(PluginCall call) {
    hideBackground();
    call.resolve();
  }

  @PluginMethod
  public void showBackground(PluginCall call) {
    showBackground();
    call.resolve();
  }

  @PluginMethod
  public void startScan(PluginCall call) {
    saveCall(call);
    scan();
  }

  @PluginMethod
  public void startScanning(PluginCall call) {
    saveCall(call);
    scan();
  }

  @PluginMethod
  public void pauseScan(PluginCall call) {
    scanningPaused = true;
    call.resolve();
  }

  @PluginMethod
  public void resumeScan(PluginCall call) {
    scanningPaused = false;
    call.resolve();
  }

  @PluginMethod
  public void stopScan(PluginCall call) {
    destroy();
    call.resolve();
  }

  @PluginMethod
  public void stopScanning(PluginCall call) {
    destroy();
    call.resolve();
  }

  @PluginMethod
  public void toggleTorch(PluginCall call) {
    if (mBarcodeView != null) {
      mBarcodeView.setTorch(!isTorchOn);
    }
    call.resolve();
  }

  @PluginMethod
  public void takePicture(PluginCall call) {
    try {
      // Open the camera
//      openCamera();

      // Create a capture request builder
      final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

      // Set the target for the capture request to the image reader
      ImageReader mImageReader = null;
      captureBuilder.addTarget(mImageReader.getSurface());

      // Set the capture request to use the auto-exposure and auto-white-balance modes
      captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

      // Determine the rotation of the image
      int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
      int imageRotation = 0;
      switch (rotation) {
        case Surface.ROTATION_0:
          imageRotation = 90;
          break;
        case Surface.ROTATION_90:
          imageRotation = 0;
          break;
        case Surface.ROTATION_180:
          imageRotation = 270;
          break;
        case Surface.ROTATION_270:
          imageRotation = 180;
          break;
      }

      // Set the capture request to use the determined rotation
      captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, imageRotation);

      // Create a capture session for the camera
      int finalImageRotation = imageRotation;
      Handler mBackgroundHandler = null;
      mCameraDevice.createCaptureSession(Arrays.asList(mImageReader.getSurface()), new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
          try {
            // Save the capture session
            CameraCaptureSession mCaptureSession = cameraCaptureSession;

            // Build the capture request
            CaptureRequest.Builder mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(mImageReader.getSurface());
            CaptureRequest mPreviewRequest = mPreviewRequestBuilder.build();

            // Set the repeating request for the capture session
            Handler mBackgroundHandler = null;
            mCaptureSession.setRepeatingRequest(mPreviewRequest, null, mBackgroundHandler);

            // Create a capture listener for the capture session
            CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
              @Override
              public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);

                // Get the captured image from the image reader
                Image image = mImageReader.acquireLatestImage();

                // Convert the image to a byte array
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                try {
                  stream.write(bytes);
                } catch (IOException e) {
                  e.printStackTrace();
                }
                byte[] imageData = stream.toByteArray();

                // Create a JSObject with the captured image data and image rotation
                JSObject resultObject = new JSObject();
                resultObject.put("imageData", imageData);
                resultObject.put("imageRotation", finalImageRotation);

                // Return the result to the plugin call
                call.resolve(resultObject);

                // Close the image reader and capture session
                image.close();
                mCaptureSession.close();
              }
            };

            // Capture the image with the capture session and listener
            mCaptureSession.capture(captureBuilder.build(), captureListener, mBackgroundHandler);
          } catch (CameraAccessException e) {
            e.printStackTrace();
          }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
          call.reject("Camera configuration failed");
        }

      }, mBackgroundHandler);
    } catch (CameraAccessException e) {
      e.printStackTrace();
    }
  }

}
