package com.cid.sample.face;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.android.camera.DrawingView;
import com.android.camera.PreviewFrameLayout;
import com.android.camera.Utils;
import com.credenceid.biometrics.Biometrics;
import com.credenceid.face.FaceEngine;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static android.hardware.Camera.Parameters.FLASH_MODE_OFF;
import static android.hardware.Camera.Parameters.FLASH_MODE_TORCH;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static com.cid.sample.face.DeviceFamily.CONE;
import static com.cid.sample.face.DeviceFamily.CTAB;
import static com.cid.sample.face.DeviceFamily.CTWO;
import static com.cid.sample.face.DeviceFamily.TRIDENT;
import static com.cid.sample.face.DeviceType.TRIDENT_1;
import static com.cid.sample.face.DeviceType.TRIDENT_2;
import static com.cid.sample.face.LaunchActivity.mBiometricsManager;
import static com.cid.sample.face.LaunchActivity.mDeviceFamily;
import static com.cid.sample.face.LaunchActivity.mDeviceType;

@SuppressLint("StaticFieldLeak")
public class CameraActivity extends Activity
		implements SurfaceHolder.Callback {
	private final static String mTAG = CameraActivity.class.getSimpleName();

	// To achieve high face detection rate we use lowest possible camera resolution for preview.
	// For the actual picture size, we will use the largest available resolution so there is no
	// loss in face image quality.
	private final static int P_WIDTH = 320;
	private final static int P_HEIGHT = 240;

	// It is always good to have a global context in case non-activity classes require it. In this
	// case "Beeper" class requires it so it may grab audio file from assets.
	private static Context mContext;

	// Different components from layout file.
	private PreviewFrameLayout mPreviewFrameLayout;
	private DrawingView mDrawingView;
	private SurfaceView mScannedImageView;
	private SurfaceHolder mSurfaceHolder;
	private TextView mStatusTextView;
	private Button mFlashOnButton;
	private Button mFlashOffButton;
	private Button mCaptureButton;

	// Camera object.
	private Camera mCamera = null;
	// If true then camera is in preview, if false it is not.
	private boolean mInPreview = false;
	// Has camera preview settings been initialized. If true yes, false otherwise. This is required
	// so camera preview does not start without it first being configured.
	private boolean mIsCameraConfigured = false;

	// Variable to keep track of which face template we are saving.
	private boolean mSaveFirstFace = true;
	// Face templates to match against with one another.
	private byte[] mFaceTemplateOne;
	private byte[] mFaceTemplateTwo;

	/* This callback is invoked after camera finishes taking a picture. */
	private Camera.PictureCallback mOnPictureTakenCallback = new Camera.PictureCallback() {
		public void onPictureTaken(byte[] data, Camera cam) {
			// Produce "camera shutter" sound so user knows that picture was captured.
			Beeper.getInstance().click();
			// Camera is no longer in preview.
			mInPreview = false;

			// Now that picture has been taken, turn off flash.
			setFlashMode(false);
			// Remove previous status, "Starting capture, hold still..." since capture is complete.
			mStatusTextView.setText("");
			// Change button to let user know they may take another picture.
			mCaptureButton.setText(getString(R.string.activity_main_recapture_label));
			// Now that camera has finished taking a picture we can allow user to re-take an iamge.
			setCaptureButtonVisibility(true);

			// Call method to run a full face detection.
			runFaceOperation(data);
		}
	};

	/* This callback is invoked on each camera preview frame. In this callback will run call face
	 * detection API and pass it preview frame.
	 */
	private Camera.PreviewCallback mCameraPreviewCallback =
			(byte[] data, Camera camera) -> detectFace(data);

	/* This callback is invoked each time camera finishes auto-focusing. */
	private Camera.AutoFocusCallback mAutoFocusCallback = new Camera.AutoFocusCallback() {
		public void onAutoFocus(boolean autoFocusSuccess, Camera arg1) {
			// Remove previus status since auto-focus is now done.
			mStatusTextView.setText("");

			// Tell DrawingView to stop displaying auto-focus circle by giving it a region of 0.
			mDrawingView.setHasTouch(false, new Rect(0, 0, 0, 0));
			mDrawingView.invalidate();

			// Re-enable capture button.
			setCaptureButtonVisibility(true);
		}
	};

	public static Context
	getContext() {
		return mContext;
	}

	// --------------------------------------------------------------------------------------------
	//
	// Methods called on Activity lifecycle.
	//
	// --------------------------------------------------------------------------------------------
	@Override
	protected void
	onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Log.d(mTAG, "onCreate()");

		setContentView(R.layout.activity_camera);

		mContext = this;
		mCamera = null;

		this.initializeLayoutComponents();
		this.configureLayoutComponents();

		this.reset();
		this.doPreview();
	}

	@Override
	protected void
	onResume() {
		super.onResume();
		Log.d(mTAG, "onResume()");

		new Thread(() -> {
			try {
				// Add a slight delay to avoid "Application passed NULL surface" error.
				Thread.sleep(50);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			runOnUiThread(() -> {
				reset();
				doPreview();
			});
		}).start();
	}

	@Override
	public void
	onBackPressed() {
		super.onBackPressed();
		Log.d(mTAG, "onBackPressed()");

		this.stopReleaseCamera();
	}

	@Override
	protected void
	onPause() {
		super.onPause();
		Log.d(mTAG, "onPause()");

		this.stopReleaseCamera();
	}

	@Override
	protected void
	onStop() {
		super.onStop();
		Log.d(mTAG, "onStop()");

		this.stopReleaseCamera();
	}

	@Override
	protected void
	onDestroy() {
		super.onDestroy();

		// Turn off flash control.
		this.setFlashMode(false);

		if (mCamera != null) {
			if (mInPreview)
				mCamera.stopPreview();

			mCamera.release();
			mCamera = null;
			mInPreview = false;
		}

		// Can only remove surface's and callbacks AFTER camera has been told to stop preview and it
		// has been released. If we did this first, then camera would try to write to an invalid
		// surface.
		mSurfaceHolder.removeCallback(this);
		this.surfaceDestroyed(mSurfaceHolder);
	}

	// --------------------------------------------------------------------------------------------
	//
	// Methods called by SurfaceHolder.Callback
	//
	// --------------------------------------------------------------------------------------------
	@Override
	public void
	surfaceChanged(SurfaceHolder holder,
				   int format,
				   int width,
				   int height) {
		Log.d(mTAG, "surfaceChanged(...)");

		if (mCamera == null) {
			Log.w(mTAG, "Camera object is NULL, will not set up preview.");
			return;
		}
		this.initPreview(width, height);
		this.startPreview();
	}

	@Override
	public void
	surfaceCreated(SurfaceHolder holder) {
	}

	@Override
	public void
	surfaceDestroyed(SurfaceHolder holder) {
		Log.d(mTAG, "surfaceDestroyed(SurfaceHolder)");

		if (mCamera == null)
			return;

		if (mInPreview)
			mCamera.stopPreview();

		mCamera.release();
		mCamera = null;
		mInPreview = false;
	}

	// --------------------------------------------------------------------------------------------
	/* Tells camera to return preview frames in a certain width/height and aspect ratio. In this
	 * case it is 320x240 frame sizes.
	 *
	 * @param width Width of preview frames to send back.
	 * @param height Height of preview frames to send back.
	 * @param ratio Aspect ration of preview frames to send back.
	 */

	//
	private void
	setPreviewSize(int width,
				   int height,
				   double ratio) {
		Camera.Parameters parameters = mCamera.getParameters();
		parameters.setPreviewSize(width, height);
		mPreviewFrameLayout.setAspectRatio(ratio);
		mCamera.setParameters(parameters);
	}
	// Methods used for initialization of layout components.
	//
	// --------------------------------------------------------------------------------------------
	/* Initializes all layout file component objects. */
	private void
	initializeLayoutComponents() {
		mPreviewFrameLayout = findViewById(R.id.preview_frame_layout);
		mDrawingView = findViewById(R.id.drawing_view);
		mScannedImageView = findViewById(R.id.scanned_imageview);

		mStatusTextView = findViewById(R.id.status_textview);
		mFlashOnButton = findViewById(R.id.flash_on_button);
		mFlashOffButton = findViewById(R.id.flash_off_button);
		mCaptureButton = findViewById(R.id.capture_button);
	}

	/* Configured all layout file component objects. Assigns listeners, configurations, etc. */
	private void
	configureLayoutComponents() {
		mPreviewFrameLayout.setVisibility(VISIBLE);
		mDrawingView.setVisibility(VISIBLE);
		mScannedImageView.setVisibility(VISIBLE);

		mSurfaceHolder = mScannedImageView.getHolder();
		mSurfaceHolder.addCallback(this);
		//noinspection deprecation
		mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

		mCaptureButton.setOnClickListener((View v) -> {
			if (!mInPreview) {
				this.reset();
				this.doPreview();

				mCaptureButton.setText(getString(R.string.activity_main_capture_label));
			} else if (mCamera != null)
				doCapture();
		});

		mFlashOnButton.setOnClickListener((View v) -> this.setFlashMode(true));
		mFlashOffButton.setOnClickListener((View v) -> this.setFlashMode(false));
	}

	// --------------------------------------------------------------------------------------------
	//
	// Methods used for camera initialization/preview/un-initialization.
	//
	// --------------------------------------------------------------------------------------------
	private void
	initPreview(int width,
				int height) {
		Log.d(mTAG, "initPreview(int, int)");

		if (mCamera == null || mSurfaceHolder.getSurface() == null) {
			Log.d(mTAG, "Either camera or SurfaceHolder was null, skip initPreview()");
			return;
		}
		if (mIsCameraConfigured) {
			Log.d(mTAG, "camera was already configured, no need now");
			return;
		}

		try {
			// Tell camera object where to display preview frames.
			mCamera.setPreviewDisplay(mSurfaceHolder);
			// Initialize camera preview in proper orientation.
			this.setCameraPreviewDisplayOrientation();

			// Get camera parameters. We will edit these, then write them back to camera.
			Camera.Parameters parameters = mCamera.getParameters();

			// Enable auto-focus if available.
			List<String> focusModes = parameters.getSupportedFocusModes();
			if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO))
				parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);

			Camera.Size size;
			if (mDeviceFamily == DeviceFamily.CTAB)
				size = Utils.getOptimalPreviewSize(width, height, parameters);
			else size = Utils.getBestPreviewSize(width, height, parameters);

			if (size == null) {
				Log.w(mTAG, "Unable to determine best preview size for camera.");
				return;
			}

			size.width = P_WIDTH;
			size.height = P_HEIGHT;

			if (mDeviceFamily == DeviceFamily.TRIDENT) {
				mPreviewFrameLayout.setAspectRatio((size.height) / (double) (size.width));
				parameters.setZoom(0);
				// TODO: Drawing view width and height.
			} else if (mDeviceFamily == DeviceFamily.CTAB) {
				Log.d(mTAG, "Configuring for C-TAB.");
				mPreviewFrameLayout.setAspectRatio((size.width) / (double) (size.height));

				// For FaceEngine we show a preview with 320x240, but the actual image is
				// captured with largest available picture size, this way we get a high
				// resolution in final image.
				Camera.Size picSize = Utils.getLargestPictureSize(parameters);
				parameters.setPictureSize(picSize.width, picSize.height);

				ViewGroup.LayoutParams params = mDrawingView.getLayoutParams();
				params.width = (int) (size.width * 2.75);
				params.height = (int) (size.height * 2.75);
				mDrawingView.setLayoutParams(params);

				// We need to set FaceEngine specific bitmap size so DrawingView knows
				// where and how to draw face detection points. Otherwise it would
				// assume the bitmap size is 0.
				mDrawingView.setBitmapDimensions(size.width, size.height);
			} else if (mDeviceFamily == CTWO) {
				Log.d(mTAG, "Configuring for C-TWO family");

				mPreviewFrameLayout.getLayoutParams().width = (int) (size.height * 2.5); //4.25);
				mPreviewFrameLayout.getLayoutParams().height = (int) (size.width * 2.5); //4.25);
				mPreviewFrameLayout.setAspectRatio(size.width / (double) size.height);

				ViewGroup.LayoutParams prevParams = mPreviewFrameLayout.getLayoutParams();
				ViewGroup.LayoutParams params = mDrawingView.getLayoutParams();

				params.width = prevParams.width;
				params.height = prevParams.height;
				mDrawingView.setLayoutParams(params);

				// We need to set FaceEngine specific bitmap size so DrawingView knows
				// where and how to draw face detection points. Otherwise it would
				// assume the bitmap size is 0.
				mDrawingView.setBitmapDimensions(P_WIDTH, P_HEIGHT);
			} else if (mDeviceFamily == DeviceFamily.CONE) {
				mPreviewFrameLayout.setAspectRatio(size.width / (double) size.height);
				// TODO: Drawing view width and height.
			}

			mCamera.setParameters(parameters);
			mIsCameraConfigured = true;
		} catch (Throwable t) {
			Log.e("PreviewDemo-Callback", "Exception in setPreviewDisplay()", t);
		}
	}

	/* Tells camera to rotate captured pictured by a certain angle. This is required since on some
	 * devices the physical camera hardware is 90 degrees, etc.
	 */
	private void
	setCameraPictureOrientation() {
		Camera.Parameters parameters = mCamera.getParameters();

		if (mDeviceType == TRIDENT_1)
			parameters.setRotation(270);
		else if (mDeviceType == TRIDENT_2)
			parameters.setRotation(180);
		else if (mDeviceFamily == CONE || mDeviceFamily == CTAB)
			parameters.setRotation(0);

		mCamera.setParameters(parameters);
	}

	/* Tells camera to rotate preview frames by a certain angle. This is required since on some
	 * devices the physical camera hardware is 90 degrees, etc.
	 */
	private void
	setCameraPreviewDisplayOrientation() {
		int orientation = 90;

		// For C-TAB, the BACK camera requires 0, but FRONT camera is 180. In this example FRONT
		// camera is not used, so that case was not programed in.
		if (mDeviceFamily == TRIDENT || mDeviceFamily == CTAB)
			orientation = 0;

		mCamera.setDisplayOrientation(orientation);
	}

	private void
	startPreview() {
		Log.d(mTAG, "startPreview()");

		if (mIsCameraConfigured && mCamera != null) {
			mStatusTextView.setText("");
			mPreviewFrameLayout.setVisibility(VISIBLE);
			mDrawingView.setVisibility(VISIBLE);
			mScannedImageView.setVisibility(VISIBLE);

			mCamera.startPreview();

			mInPreview = true;
			mCaptureButton.setText(getString(R.string.activity_main_capture_label));
			this.setCaptureButtonVisibility(true);
		} else Log.w(mTAG, "Camera not configured, aborting start preview.");
	}

	private void
	doPreview() {
		Log.d(mTAG, "doPreview()");

		try {
			// If camera was not already opened, open it.
			if (mCamera == null) {
				mCamera = Camera.open();

				// Tells camera to give us preview frames in these dimensions.
				this.setPreviewSize(P_WIDTH, P_HEIGHT, (double) P_WIDTH / P_HEIGHT);
			}

			if (mCamera != null) {
				Log.d(mTAG, "Camera opened, setting preview buffers, surfaces, etc.");

				// Tell camera where to draw frames to.
				mCamera.setPreviewDisplay(mSurfaceHolder);
				// Tell camera to invoke this callback on each frame.
				mCamera.setPreviewCallback(mCameraPreviewCallback);
				// Tell camera to rotate preview frames to proper orientation, based on DeviceType.
				this.setCameraPreviewDisplayOrientation();
				// Now we can tell camera to start preview frames.
				this.startPreview();
			}
		} catch (Exception e) {
			Log.e(mTAG, "Failed to start preview: " + e.getLocalizedMessage());
			if (mCamera != null)
				mCamera.release();

			mCamera = null;
			mInPreview = false;
		}
	}

	/* Captures image, before capturing image it will set proper picture orientation. */
	@SuppressLint("SetTextI18n")
	private void
	doCapture() {
		//this.reset();
		this.setCameraPictureOrientation();

		if (mCamera != null) {
			this.setCaptureButtonVisibility(false);
			mStatusTextView.setText("Starting capture, hold still...");

			// We are no longer going to be in preview. Set variable BEFORE telling camera to take
			// picture. Camera takes time to take a picture so we do not want any preview event to
			// take place while a picture is being captured.
			mInPreview = false;
			mCamera.takePicture(null, null, null, mOnPictureTakenCallback);
		}
	}

	private void
	stopReleaseCamera() {
		if (mCamera != null) {
			// Tell camera to no longer invoke callback on each preview frame.
			mCamera.setPreviewCallback(null);
			// Turn off flash.
			this.setFlashMode(false);

			// Stop camera preview.
			if (mInPreview)
				mCamera.stopPreview();

			// Release camera and nullify object.
			mCamera.release();
			mCamera = null;
			// We are no longer in preview mode.
			mInPreview = false;
		}

		// Remove camera surfaces.
		mSurfaceHolder.removeCallback(this);
		this.surfaceDestroyed(mSurfaceHolder);
	}

	// --------------------------------------------------------------------------------------------
	//
	// Methods related to UI control.
	//
	// --------------------------------------------------------------------------------------------
	/* This method either hides or shows capture button allowing user to capture an image. This is
	 * required because while camera is focusing user should not be allowed to press capture. Once
	 * focusing finishes and a clear preview is available, only then should an image be allowed to
	 * be taken.
	 *
	 * @param visibility If true button is shown, if false button is hidden.
	 */
	private void
	setCaptureButtonVisibility(boolean visibility) {
		mCaptureButton.setVisibility(visibility ? VISIBLE : INVISIBLE);
	}

	/* Sets camera flash.
	 *
	 * @param useFlash If true turns on flash, if false disables flash.
	 */
	private void
	setFlashMode(boolean useFlash) {
		Log.d(mTAG, "setFlashMode(boolean)");

		// If camera object was destroyed, there is nothing to do.
		if (mCamera == null)
			return;

		// Camera flash parameters do not work on TAB/TRIDENT devices. In order to use flash on
		// these devices you must use the Credence APIs.
		if (mDeviceFamily == DeviceFamily.CTAB || mDeviceFamily == DeviceFamily.TRIDENT)
			mBiometricsManager.cameraFlashControl(useFlash);
		else {
			try {
				Camera.Parameters p = mCamera.getParameters();
				p.setFlashMode(useFlash ? FLASH_MODE_TORCH : FLASH_MODE_OFF);
				mCamera.setParameters(p);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/* Resets camera flash and UI back to camera preview state. */
	private void
	reset() {
		Log.d(mTAG, "reset()");

		// This method is called before we start a camera preview, so we update global variable.
		mInPreview = true;

		// Change capture button image to "Capture".
		mCaptureButton.setText(getString(R.string.activity_main_capture_label));

		// Turn off flash since new preview.
		this.setFlashMode(false);

		// Display all buttons in their proper states.
		this.setCaptureButtonVisibility(true);
	}

	// --------------------------------------------------------------------------------------------
	//
	// Methods used for performing some type of operation/processing.
	//
	// --------------------------------------------------------------------------------------------
	/* Attempts to perform tap-to-focus on camera with given focus region.
	 *
	 * @param touchRect Region to focus on.
	 */
	@SuppressLint("SetTextI18n")
	public void
	performTapToFocus(final Rect touchRect) {
		Log.d(mTAG, "performTapToFocus(Rect)");

		if (!mInPreview)
			return;

		this.setCaptureButtonVisibility(false);
		mStatusTextView.setText("Auto-focusing, please wait...");

		final int one = 2000, two = 1000;

		// Here we properly bound our Rect for a better tap to focus region
		final Rect targetFocusRect = new Rect(
				touchRect.left * one / mDrawingView.getWidth() - two,
				touchRect.top * one / mDrawingView.getHeight() - two,
				touchRect.right * one / mDrawingView.getWidth() - two,
				touchRect.bottom * one / mDrawingView.getHeight() - two);

		// Since Camera parameters only accept a List of  areas to focus, create a
		// list.
		final List<Camera.Area> focusList = new ArrayList<>();
		// Convert Graphics.Rect to Camera.Rect for camera parameters to understand.
		// Add custom focus Rect. region to focus list.
		focusList.add(new Camera.Area(targetFocusRect, 1000));

		// For certain device auto-focus parameters need to be explicitly setup.
		if (mDeviceFamily == CONE) {
			Camera.Parameters para = mCamera.getParameters();
			para.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
			para.setFocusAreas(focusList);
			para.setMeteringAreas(focusList);
			mCamera.setParameters(para);
		}

		// Call mCamera AutoFocus and pass callback to be called when auto focus finishes
		mCamera.autoFocus(mAutoFocusCallback);
		// Tell our drawing view we have a touch in the given Rect
		mDrawingView.setHasTouch(true, touchRect);
		// Tell our drawing view to Update
		mDrawingView.invalidate();
	}

	/* Attempts to detect face Rect. region in given image. If face image is found it updates
	 * DrawingView on where to draw Rectangle and then tells it to perform an "onDraw()".
	 *
	 * @param bitmapBytes Bitmap image in byte format to run detection on.
	 */
	private void
	detectFace(byte[] bitmapBytes) {
		// If camera was closed, immediately after a preview callback exit out, this is to prevent
		// NULL pointer exceptions when using the camera object later on.
		if (mCamera == null || bitmapBytes == null)
			return;

		// We need to stop camera preview callbacks from continuously being invoked while processing
		// is going on. Otherwise we would have a backlog of frames needing to be processed. To fix
		// this we remove preview callback, then re-enable it post-processing.
		//
		// - Preview callback invoked.
		// -- Tell camera to sto preview callbacks.
		// **** Meanwhile camera is still receiving frames, but continues to draw them. ****
		// -- Process camera preview frame.
		// -- Draw detected face Rect.
		// -- Tell camera to invoke preview callback with next frame.
		//
		// Using this technique does not drop camera frame-rate, so camera does not look "laggy".
		// Instead now we use every 5-th frame for face detection.
		mCamera.setPreviewCallback(null);

		// Need to fix color format of raw camera preview frames.
		ByteArrayOutputStream outStream = new ByteArrayOutputStream();
		Rect rect = new Rect(0, 0, 320, 240);
		YuvImage yuvimage = new YuvImage(bitmapBytes, ImageFormat.NV21, 320, 240, null);
		yuvimage.compressToJpeg(rect, 100, outStream);

		// Save fixed color image as final good Bitmap.
		Bitmap bm = BitmapFactory.decodeByteArray(outStream.toByteArray(), 0, outStream.size());

		//		if (VIBEApplication.isDeviceOfType(CREDENCE_TWO_FAMILY)) {
		//			mFinalBitmap = ImageTools.Editor.Rotate(mFinalBitmap, 90);
		//		}

		// Detect face on finalized Bitmap image.
		mBiometricsManager.detectFace(bm, (Biometrics.ResultCode resultCode,
										   RectF rectF) -> {
			// If camera was closed or preview stopped, immediately exit out. This is done so that
			// we do not continue to process invalid frames, or draw to NULL surfaces.
			if (mCamera == null || !mInPreview)
				return;

			// Tell camera to start preview callbacks again.
			mCamera.setPreviewCallback(mCameraPreviewCallback);

			if (resultCode == Biometrics.ResultCode.OK) {
				// Tell view that it will need to draw a detected face's Rect. region.
				mDrawingView.setHasFace(true);

				Log.d(mTAG, "FaceRect: " + rectF.toString());

				if (mDeviceFamily == CTWO) {
					mDrawingView.setFaceRect(rectF.left + 40,
							rectF.top - 50,
							rectF.right + 40,
							rectF.bottom - 50);
				} else {
					mDrawingView.setFaceRect(rectF.left,
							rectF.top,
							rectF.right,
							rectF.bottom);
				}
			} else {
				// Tell view to not draw face Rect. region on next "onDraw()" call.
				mDrawingView.setHasFace(false);
			}

			// Tell view to invoke an "onDraw()".
			mDrawingView.invalidate();
		});
	}

	private void
	runFaceOperation(byte[] bitmapBytes) {
		// If camera was closed, immediately after a preview callback exit out, this is to prevent
		// NULL pointer exceptions when using the camera object later on.
		if (mCamera == null || bitmapBytes == null)
			return;

		// Run template creation/matching on a different thread so that to the user it looks very
		// fast with no UI lag.
		new Thread(() -> {
			Bitmap bm = BitmapFactory.decodeByteArray(bitmapBytes, 0, bitmapBytes.length);

			// Create a face template for matching.
			Log.d(mTAG, "Calling API for face template creation.");
			mBiometricsManager.createFaceTemplate(bm, (Biometrics.ResultCode resultCode,
													   byte[] template) -> {
				if (resultCode == Biometrics.ResultCode.OK) {
					// We want to save first template to first array and second template to second
					// array. We have a variable to keep track of there to store templates to.
					if (mSaveFirstFace) {
						mFaceTemplateOne = template;
						mSaveFirstFace = false;
					} else {
						mFaceTemplateTwo = template;
						mSaveFirstFace = true;

						// Now that both templates have been saved, we can do a match.
						matchFaceTemplates(mFaceTemplateOne, mFaceTemplateTwo);
					}
				}
			});

			// Run a full face analysis.
			runFullFaceDetection(bm);
		}).start();
	}

	/* Performs a full face detection with age, gender, head pose estimation, etc. detection.
	 *
	 * @param bitmap Bitmap image to run face analysis on.
	 */
	private void
	runFullFaceDetection(Bitmap bitmap) {
		// Perform a more in-depth analysis of face detection.
		mBiometricsManager.analyzeFaceImage(bitmap, (Biometrics.ResultCode resultCode,
													 RectF rectF,
													 ArrayList<PointF> landmark5,
													 ArrayList<PointF> landmark68,
													 float[] floats,
													 FaceEngine.HeadPoseDirection[] poseDirections,
													 FaceEngine.Gender gender,
													 int age,
													 FaceEngine.Emotion emotion,
													 boolean glasses,
													 int imageQuality) -> {
			if (resultCode != Biometrics.ResultCode.OK)
				return;

			Log.d(mTAG, "PoseDirections[Roll, Pitch, Yaw]: "
					+ poseDirections[0].name() + ", "
					+ poseDirections[1].name() + ", "
					+ poseDirections[2].name());
			Log.d(mTAG, "Gender: " + gender.name());
			Log.d(mTAG, "Age: " + age);
			Log.d(mTAG, "Emotion: " + emotion.name());
			Log.d(mTAG, "Has Glasses: " + glasses);
			Log.d(mTAG, "Image Quality: " + imageQuality);
		});
	}

	/* Matches two face templates.
	 *
	 * @param faceTemplateOne First template to match.
	 * @param faceTemplateTwo Second template to match.
	 */
	private void
	matchFaceTemplates(byte[] faceTemplateOne,
					   byte[] faceTemplateTwo) {
		Log.d(mTAG, "matchFaceTemplates(byte[], byte[])");

		// Display a dialog letting user know that some processing is happening.
		AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
		alertDialogBuilder.setTitle("Face Match Score");
		alertDialogBuilder.setCancelable(false);
		alertDialogBuilder.setMessage("Calculating score, please wait...");
		AlertDialog alertDialog = alertDialogBuilder.create();

		// Remember that this function is running inside a thread, so all UI operations MUST be
		// done on the UI thread.
		runOnUiThread(alertDialog::show);

		if (faceTemplateOne == null || faceTemplateOne.length == 0
				|| faceTemplateTwo == null || faceTemplateTwo.length == 0) {

			runOnUiThread(() -> {
				alertDialog.dismiss();
				alertDialog.setMessage("Score: 0");
				alertDialog.show();
			});
			return;
		}

		mBiometricsManager.matchFaceTemplates(faceTemplateOne, faceTemplateTwo,
				(Biometrics.ResultCode resultCode, int matchScore) ->
						runOnUiThread(() -> {
							// Remove old dialog, update dialog UI, then re-show new dialog.
							alertDialog.dismiss();

							alertDialogBuilder.setPositiveButton("Ok", (DialogInterface dialog,
																		int which) -> {
							});

							if (resultCode == Biometrics.ResultCode.OK)
								alertDialogBuilder.setMessage("Score: " + matchScore);
							else alertDialogBuilder.setMessage("Score: 0");

							alertDialogBuilder.show();
						}));

	}
}
