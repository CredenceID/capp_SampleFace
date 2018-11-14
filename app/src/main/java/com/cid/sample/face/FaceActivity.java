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
import android.os.Environment;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import com.android.camera.DrawingView;
import com.android.camera.PreviewFrameLayout;
import com.android.camera.Utils;
import com.cid.sample.face.models.DeviceFamily;
import com.cid.sample.face.models.DeviceType;
import com.credenceid.biometrics.Biometrics;
import com.credenceid.biometrics.BiometricsManager;
import com.credenceid.face.FaceEngine.Emotion;
import com.credenceid.face.FaceEngine.Gender;
import com.credenceid.face.FaceEngine.HeadPoseDirection;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static android.hardware.Camera.Parameters.FLASH_MODE_OFF;
import static android.hardware.Camera.Parameters.FLASH_MODE_TORCH;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;
import static com.cid.sample.face.models.DeviceFamily.CONE;
import static com.cid.sample.face.models.DeviceFamily.CTAB;
import static com.cid.sample.face.models.DeviceFamily.CTWO;
import static com.cid.sample.face.models.DeviceFamily.TRIDENT;
import static com.cid.sample.face.models.DeviceType.TRIDENT_1;
import static com.cid.sample.face.models.DeviceType.TRIDENT_2;
import static com.credenceid.biometrics.Biometrics.ResultCode.FAIL;

//TODO: Fix/add comments.
public class FaceActivity
		extends Activity
		implements SurfaceHolder.Callback {

	private final static String TAG = FaceActivity.class.getSimpleName();

	/* To obtain high face detection rate we use lowest possible camera resolution for preview.
	 * For the actual picture size, we will use the largest available resolution so there is no
	 * loss in face image quality.
	 */
	private final static int P_WIDTH = 320;
	private final static int P_HEIGHT = 240;

	private static final int IMAGE_WIDTH_4_BY_3_8MP = 3264;
	private static final int IMAGE_HEIGHT_4_BY_3_8MP = 2448;

	private static final int COMPRESSION_QUALITY = 100;

	/* It is always good to have a global context in case non-activity classes require it. In this
	 * case "Beeper" class requires it so it may grab audio file from assets.
	 */
	@SuppressLint("StaticFieldLeak")
	private static Context mContext;

	/* CredenceSDK biometrics object, used to interface with APIs. */
	@SuppressLint("StaticFieldLeak")
	private static BiometricsManager mBiometricsManager;
	/* Stores which Credence family of device's this app is running on. */
	private static DeviceFamily mDeviceFamily = DeviceFamily.CID_PRODUCT;
	/* Stores which specific device this app is running on. */
	private static DeviceType mDeviceType = DeviceType.CID_PRODUCT;

	/* Absolute paths of where face images are stores on disk. */
	private final File mFiveMPFile
			= new File(Environment.getExternalStorageDirectory() + "/c-sdkapp_5mp.jpg");
	private final File mEightMPFile
			= new File(Environment.getExternalStorageDirectory() + "/c-sdkapp_8mp.jpg");

	/*
	 * Components in layout file.
	 */
	private PreviewFrameLayout mPreviewFrameLayout;
	private DrawingView mDrawingView;
	private SurfaceView mScannedImageView;
	private SurfaceHolder mSurfaceHolder;
	private TextView mStatusTextView;
	private Button mFlashOnButton;
	private Button mFlashOffButton;
	private Button mCaptureButton;
	private CheckBox mEightMPCheckbox;

	private Camera mCamera = null;

	/* If true then camera is in preview, if false it is not. */
	private boolean mInPreview = false;
	/* Has camera preview settings been initialized. If true yes, false otherwise. This is required
	 * so camera preview does not start without it first being configured.
	 */
	private boolean mIsCameraConfigured = false;

	/* This callback is invoked after camera finishes taking a picture. */
	private Camera.PictureCallback mOnPictureTakenCallback = new Camera.PictureCallback() {
		public void onPictureTaken(byte[] data, Camera cam) {
			/* Produce "camera shutter" sound so user knows that picture was captured. */
			Beeper.getInstance().click();

			/* Now that picture has been taken, turn off flash. */
			setFlashMode(false);

			/* Camera is no longer in preview. */
			mInPreview = false;

			/* Remove previous status since capture is complete. */
			mStatusTextView.setText("");
			/* Change button to let user know they may take another picture. */
			mCaptureButton.setText(getString(R.string.recapture_label));
			/* Allow user to re-take an image. */
			setCaptureButtonVisibility(true);

			Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
			if (CTWO == mDeviceFamily)
				bitmap = Utils.rotateBitmap(bitmap, 90);

			saveImage(bitmap, mEightMPCheckbox.isChecked());
			analyzeImage(bitmap);
		}
	};

	/* This callback is invoked on each camera preview frame. In this callback will run call face
	 * detection API and pass it preview frame.
	 */
	private Camera.PreviewCallback mCameraPreviewCallback
			= (byte[] data, Camera camera) -> detectFace(data);

	/* This callback is invoked each time camera finishes auto-focusing. */
	private Camera.AutoFocusCallback mAutoFocusCallback = new Camera.AutoFocusCallback() {
		public void onAutoFocus(boolean autoFocusSuccess, Camera arg1) {
			/* Remove previous status since auto-focus is now done. */
			mStatusTextView.setText("");

			/* Tell DrawingView to stop displaying auto-focus circle by giving it a region of 0. */
			mDrawingView.setHasTouch(false, new Rect(0, 0, 0, 0));
			mDrawingView.invalidate();

			/* Re-enable capture button. */
			setCaptureButtonVisibility(true);
		}
	};

	public static Context
	getContext() {
		return mContext;
	}

	@Override
	protected void
	onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_face);

		mBiometricsManager = LaunchActivity.getBiometricsManager();
		mDeviceFamily = LaunchActivity.getDeviceFamily();
		mDeviceType = LaunchActivity.getDeviceType();

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

		new Thread(() -> {
			try {
				/* Add a slight delay to avoid "Application passed NULL surface" error. */
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

	/* This is required to stop camera every time back button is pressed.  */
	@Override
	public void
	onBackPressed() {
		super.onBackPressed();

		this.stopReleaseCamera();

		finish();
	}

	/* This is required to stop camera preview every time activity loses focus. */
	@Override
	protected void
	onPause() {
		super.onPause();

		this.stopReleaseCamera();
	}

	/* This is required to stop camera every time application is killed.  */
	@Override
	protected void
	onStop() {
		super.onStop();

		this.stopReleaseCamera();
	}

	@Override
	protected void
	onDestroy() {
		super.onDestroy();

		this.setFlashMode(false);

		if (mCamera != null) {
			if (mInPreview)
				mCamera.stopPreview();

			mCamera.release();
			mCamera = null;
			mInPreview = false;
		}

		/* Can only remove surface's and callbacks AFTER camera has been told to stop preview and it
		 * has been released. If we did this first, then camera would try to write to an invalid
		 * surface.
		 */
		mSurfaceHolder.removeCallback(this);

		this.surfaceDestroyed(mSurfaceHolder);
	}

	@Override
	public void
	surfaceChanged(SurfaceHolder holder,
				   int format,
				   int width,
				   int height) {

		if (mCamera == null) {
			Log.w(TAG, "Camera object is NULL, will not set up preview.");
			return;
		}

		this.initPreview();
		this.startPreview();
	}

	@Override
	public void
	surfaceCreated(SurfaceHolder holder) {
	}

	@Override
	public void
	surfaceDestroyed(SurfaceHolder holder) {
		if (mCamera == null)
			return;

		if (mInPreview)
			mCamera.stopPreview();

		mCamera.release();
		mCamera = null;
		mInPreview = false;
	}

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
		mEightMPCheckbox = findViewById(R.id.eight_mp_checkbox);
	}

	/* Configured all layout file component objects. Assigns listeners, configurations, etc. */
	@SuppressWarnings("deprecation")
	private void
	configureLayoutComponents() {
		this.setFlashButtonVisibility(true);

		/* Only CredenceTAB family of device's support 8MP back camera resolution.  */
		if (mDeviceFamily != CTAB)
			mEightMPCheckbox.setVisibility(View.GONE);

		mPreviewFrameLayout.setVisibility(VISIBLE);
		mDrawingView.setVisibility(VISIBLE);
		mScannedImageView.setVisibility(VISIBLE);

		mSurfaceHolder = mScannedImageView.getHolder();
		mSurfaceHolder.addCallback(this);
		mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

		mCaptureButton.setOnClickListener((View v) -> {
			if (!mInPreview) {
				this.reset();
				this.doPreview();

				mCaptureButton.setText(getString(R.string.capture_label));
			} else if (mCamera != null)
				doCapture();
		});

		mFlashOnButton.setOnClickListener((View v) -> this.setFlashMode(true));
		mFlashOffButton.setOnClickListener((View v) -> this.setFlashMode(false));
	}

	private void
	initPreview() {
		if (mCamera == null || mSurfaceHolder.getSurface() == null) {
			Log.d(TAG, "Either camera or SurfaceHolder was null, skip initPreview()");
			return;
		}
		if (mIsCameraConfigured) {
			Log.d(TAG, "camera was already configured, no need now");
			return;
		}

		try {
			/* Tell camera object where to display preview frames. */
			mCamera.setPreviewDisplay(mSurfaceHolder);
			/* Initialize camera preview in proper orientation. */
			this.setCameraPreviewDisplayOrientation();

			/* Get camera parameters. We will edit these, then write them back to camera. */
			Camera.Parameters parameters = mCamera.getParameters();

			/* Enable auto-focus if available. */
			List<String> focusModes = parameters.getSupportedFocusModes();
			if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO))
				parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);

			/* For FaceEngine we show a preview with 320x240, but the actual image is
			 * captured with largest available picture size, this way we get a high
			 * resolution in final image.
			 */
			Camera.Size picSize = Utils.getLargestPictureSize(parameters);
			parameters.setPictureSize(picSize.width, picSize.height);

			/* Regardless of what size is returned we always use a 320x240 preview size for face
			 * detection since it is extremely fast.
			 *
			 * This previewSize is used to set up dimensions of all camera views.
			 */
			Camera.Size previewSize = parameters.getPreviewSize();
			previewSize.width = P_WIDTH;
			previewSize.height = P_HEIGHT;

			if (CTWO == mDeviceFamily) {
				mPreviewFrameLayout.getLayoutParams().width = (int) (previewSize.height * 2.5);
				mPreviewFrameLayout.getLayoutParams().height = (int) (previewSize.width * 2.5);
			}
			mPreviewFrameLayout.setAspectRatio((previewSize.width) / (double) (previewSize.height));

			ViewGroup.LayoutParams drawingViewLayoutParams= mDrawingView.getLayoutParams();

			if (CTAB == mDeviceFamily) {
				drawingViewLayoutParams.width = (int) (previewSize.width * 2.75);
				drawingViewLayoutParams.height = (int) (previewSize.height * 2.75);
			} else {
				ViewGroup.LayoutParams prevParams = mPreviewFrameLayout.getLayoutParams();
				drawingViewLayoutParams.width = prevParams.width;
				drawingViewLayoutParams.height = prevParams.height;
			}
			mDrawingView.setLayoutParams(drawingViewLayoutParams);

			/* Need to set FaceEngine specific bitmap size so DrawingView knows
			 * where and how to draw face detection points. Otherwise it would
			 * assume the bitmap size is 0.
			 */
			mDrawingView.setBitmapDimensions(P_WIDTH, P_HEIGHT);

			mCamera.setParameters(parameters);
			mIsCameraConfigured = true;
		} catch (Throwable t) {
			Log.e("PreviewDemo-Callback", "Exception in setPreviewDisplay()", t);
		}
	}

	/* Tells camera to return preview frames in a certain width/height and aspect ratio. In this
	 * case it is 320x240 frame sizes.
	 *
	 * @param width Width of preview frames to send back.
	 * @param height Height of preview frames to send back.
	 * @param ratio Aspect ration of preview frames to send back.
	 */
	@SuppressWarnings("SameParameterValue")
	private void
	setPreviewSize(int width,
				   int height,
				   double ratio) {

		Camera.Parameters parameters = mCamera.getParameters();
		parameters.setPreviewSize(width, height);
		mPreviewFrameLayout.setAspectRatio(ratio);
		mCamera.setParameters(parameters);
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

		/* For C-TAB, the BACK camera requires 0, but FRONT camera is 180. In this example FRONT
		 * camera is not used, so that case was not programed in.
		 */
		if (mDeviceFamily == TRIDENT || mDeviceFamily == CTAB)
			orientation = 0;

		mCamera.setDisplayOrientation(orientation);
	}

	private void
	startPreview() {
		if (mIsCameraConfigured && mCamera != null) {
			mStatusTextView.setText("");
			mPreviewFrameLayout.setVisibility(VISIBLE);
			mDrawingView.setVisibility(VISIBLE);
			mScannedImageView.setVisibility(VISIBLE);

			mCamera.startPreview();

			mInPreview = true;
			mCaptureButton.setText(getString(R.string.capture_label));
			this.setCaptureButtonVisibility(true);
		} else Log.w(TAG, "Camera not configured, aborting start preview.");
	}

	private void
	doPreview() {
		try {
			/* If camera was not already opened, open it. */
			if (mCamera == null) {
				mCamera = Camera.open();

				/* Tells camera to give us preview frames in these dimensions. */
				this.setPreviewSize(P_WIDTH, P_HEIGHT, (double) P_WIDTH / P_HEIGHT);
			}

			if (mCamera != null) {
				/* Tell camera where to draw frames to. */
				mCamera.setPreviewDisplay(mSurfaceHolder);
				/* Tell camera to invoke this callback on each frame. */
				mCamera.setPreviewCallback(mCameraPreviewCallback);
				/* Rotate preview frames to proper orientation based on DeviceType. */
				this.setCameraPreviewDisplayOrientation();
				/* Now we can tell camera to start preview frames. */
				this.startPreview();
			}
		} catch (Exception e) {
			Log.e(TAG, "Failed to start preview: " + e.getLocalizedMessage());
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
		this.setCameraPictureOrientation();

		if (mCamera != null) {
			this.setCaptureButtonVisibility(false);
			mStatusTextView.setText(getString(R.string.start_capture_hold_still));

			/* We are no longer going to be in preview. Set variable BEFORE telling camera to take
			 * picture. Camera takes time to take a picture so we do not want any preview event to
			 * take place while a picture is being captured.
			 */
			mInPreview = false;
			mCamera.takePicture(null, null, null, mOnPictureTakenCallback);
		}
	}

	private void
	stopReleaseCamera() {
		if (mCamera != null) {
			/* Tell camera to no longer invoke callback on each preview frame. */
			mCamera.setPreviewCallback(null);
			/* Turn off flash. */
			this.setFlashMode(false);

			/* Stop camera preview. */
			if (mInPreview)
				mCamera.stopPreview();

			/* Release camera and nullify object. */
			mCamera.release();
			mCamera = null;
			/* We are no longer in preview mode. */
			mInPreview = false;
		}

		/* Remove camera surfaces. */
		mSurfaceHolder.removeCallback(this);
		this.surfaceDestroyed(mSurfaceHolder);
	}

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

	/* This method either hides or shows flash buttons allowing user to control flash. This is
	 * required because after an image is captured a user should not be allowed to control flash
	 * since camera is no longer in preview. Instead of disabling the buttons we hide them from
	 * the user.
	 *
	 * @param visibility If true buttons are show, if false they are hidden.
	 */
	@SuppressWarnings("SameParameterValue")
	private void
	setFlashButtonVisibility(boolean visibility) {
		mFlashOnButton.setVisibility(visibility ? VISIBLE : INVISIBLE);
		mFlashOffButton.setVisibility(visibility ? VISIBLE : INVISIBLE);
	}

	/* Sets camera flash.
	 *
	 * @param useFlash If true turns on flash, if false disables flash.
	 */
	private void
	setFlashMode(boolean useFlash) {
		/* If camera object was destroyed, there is nothing to do. */
		if (mCamera == null)
			return;

		/* Camera flash parameters do not work on TAB/TRIDENT devices. In order to use flash on
		 * these devices you must use the Credence APIs.
		 */
		if (mDeviceFamily == CTAB || mDeviceFamily == TRIDENT)
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
		Log.d(TAG, "reset()");

		/* This method is called before we start a camera preview, so we update global variable. */
		mInPreview = true;

		/* Change capture button image to "Capture". */
		mCaptureButton.setText(getString(R.string.capture_label));

		/* Turn off flash since new preview. */
		this.setFlashMode(false);

		/* Display all buttons in their proper states. */
		this.setCaptureButtonVisibility(true);
		this.setFlashButtonVisibility(true);
	}

	/* Attempts to perform tap-to-focus on camera with given focus region.
	 *
	 * @param touchRect Region to focus on.
	 */
	@SuppressLint("SetTextI18n")
	public void
	performTapToFocus(final Rect touchRect) {
		if (!mInPreview)
			return;

		this.setCaptureButtonVisibility(false);
		mStatusTextView.setText(getString(R.string.autofocus_wait));

		final int one = 2000, two = 1000;

		/* Here we properly bound our Rect for a better tap to focus region */
		final Rect targetFocusRect = new Rect(
				touchRect.left * one / mDrawingView.getWidth() - two,
				touchRect.top * one / mDrawingView.getHeight() - two,
				touchRect.right * one / mDrawingView.getWidth() - two,
				touchRect.bottom * one / mDrawingView.getHeight() - two);

		/* Since Camera parameters only accept a List of  areas to focus, create a list. */
		final List<Camera.Area> focusList = new ArrayList<>();
		/* Convert Graphics.Rect to Camera.Rect for camera parameters to understand.
		 * Add custom focus Rect. region to focus list.
		 */
		focusList.add(new Camera.Area(targetFocusRect, 1000));

		/* For certain device auto-focus parameters need to be explicitly setup. */
		if (mDeviceFamily == CONE) {
			Camera.Parameters para = mCamera.getParameters();
			para.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
			para.setFocusAreas(focusList);
			para.setMeteringAreas(focusList);
			mCamera.setParameters(para);
		}

		/* Call mCamera AutoFocus and pass callback to be called when auto focus finishes */
		mCamera.autoFocus(mAutoFocusCallback);
		/* Tell our drawing view we have a touch in the given Rect */
		mDrawingView.setHasTouch(true, touchRect);
		/* Tell our drawing view to Update */
		mDrawingView.invalidate();
	}

	/* Attempts to detect face Rect. region in given image. If face image is found it updates
	 * DrawingView on where to draw Rectangle and then tells it to perform an "onDraw()".
	 *
	 * @param bitmapBytes Bitmap image in byte format to run detection on.
	 */
	private void
	detectFace(byte[] bitmapBytes) {
		/* If camera was closed, immediately after a preview callback exit out, this is to prevent
		 * NULL pointer exceptions when using the camera object later on.
		 */
		if (mCamera == null || bitmapBytes == null)
			return;

		/* We need to stop camera preview callbacks from continuously being invoked while processing
		 * is going on. Otherwise we would have a backlog of frames needing to be processed. To fix
		 * this we remove preview callback, then re-enable it post-processing.
		 *
		 * - Preview callback invoked.
		 * -- Tell camera to sto preview callbacks.
		 * **** Meanwhile camera is still receiving frames, but continues to draw them. ****
		 * -- Process camera preview frame.
		 * -- Draw detected face Rect.
		 * -- Tell camera to invoke preview callback with next frame.
		 *
		 * Using this technique does not drop camera frame-rate, so camera does not look "laggy".
		 * Instead now we use every 5-th frame for face detection.
		 */
		mCamera.setPreviewCallback(null);

		/* Need to fix color format of raw camera preview frames. */
		ByteArrayOutputStream outStream = new ByteArrayOutputStream();
		Rect rect = new Rect(0, 0, 320, 240);
		YuvImage yuvimage = new YuvImage(bitmapBytes, ImageFormat.NV21, 320, 240, null);
		yuvimage.compressToJpeg(rect, 100, outStream);

		/* Save fixed color image as final good Bitmap. */
		Bitmap bm = BitmapFactory.decodeByteArray(outStream.toByteArray(), 0, outStream.size());

		/* On CredenceTWO device's captured image is rotated by 270 degrees. To fix this rotate
		 * image by another 90 degrees to have it right-side-up.
		 */
		if (CTWO == mDeviceFamily)
			bm = Utils.rotateBitmap(bm, 90);

		/* Detect face on finalized Bitmap image. */
		mBiometricsManager.detectFace(bm, (Biometrics.ResultCode resultCode,
										   RectF rectF) -> {
			/* If camera was closed or preview stopped, immediately exit out. This is done so that
			 * we do not continue to process invalid frames, or draw to NULL surfaces.
			 */
			if (mCamera == null || !mInPreview)
				return;

			/* Tell camera to start preview callbacks again. */
			mCamera.setPreviewCallback(mCameraPreviewCallback);

			if (resultCode == Biometrics.ResultCode.OK) {
				/* Tell view that it will need to draw a detected face's Rect. region. */
				mDrawingView.setHasFace(true);

				/* If a CredenceTWO device then bounding Rect needs to be scaled to properly fit. */
				if (CTWO == mDeviceFamily) {
					mDrawingView.setFaceRect(rectF.left + 40,
							rectF.top - 25,
							rectF.right + 40,
							rectF.bottom - 50);
				} else {
					mDrawingView.setFaceRect(rectF.left,
							rectF.top,
							rectF.right,
							rectF.bottom);
				}
			} else {
				/* Tell view to not draw face Rect. region on next "onDraw()" call. */
				mDrawingView.setHasFace(false);
			}

			/* Tell view to invoke an "onDraw()". */
			mDrawingView.invalidate();
		});
	}

	/* Performs a full face analysis (via CredenceSDK) on given image.
	 *
	 * @param bitmap Image to run full face analysis on.
	 */
	@SuppressWarnings("StringConcatenationInLoop")
	private void
	analyzeImage(final Bitmap bitmap) {
		if (null == bitmap)
			return;

		/* Start by displaying a popup to let user known a background operation is taking place. */
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setCancelable(false);
		builder.setIcon(R.drawable.ic_launcher);
		builder.setTitle(getString(R.string.face_engine_analytics_title));
		builder.setMessage(getString(R.string.face_engine_analyzing_wait));

		AlertDialog alertDialog = builder.create();
		alertDialog.show();

		/* Make API call to run full face analysis. */
		mBiometricsManager.analyzeFaceImage(bitmap, (Biometrics.ResultCode resultCode,
													 RectF rectF,
													 ArrayList<PointF> arrayList,
													 ArrayList<PointF> arrayList1,
													 float[] floats,
													 HeadPoseDirection[] poseDirections,
													 Gender gender,
													 int age,
													 Emotion emotion,
													 boolean glasses,
													 int imageQuality) -> {

			if (FAIL == resultCode)
				builder.setMessage(getString(R.string.face_engine_fail));
			else {

				String displayData = "HeadPose: ";
				for (HeadPoseDirection pose : poseDirections)
					displayData += (pose.name() + " ");

				displayData += ("\nGender: " + gender.name());
				displayData += ("\nAge: " + age);
				displayData += ("\nEmotion: " + emotion.name());
				displayData += ("\nImage Quality: " + imageQuality);

				builder.setMessage(displayData);
			}

			/* Remove popup, update its message with result from API call, and re-display. */
			alertDialog.dismiss();
			builder.setPositiveButton("OK", (DialogInterface dialog,
											 int which) -> {
			});
			builder.create().show();
		});
	}

	/* Saves a given image (in byte array format) to disk. If image is to be saved as 8MP then
	 * image is scaled to match appropriate resolution.
	 *
	 * @param rawData Image in byte array format to be saved.
	 * @param isEightMP If true image is saved with 8MP dimensions, else default dimensions.
	 */
	@SuppressWarnings("ResultOfMethodCallIgnored")
	private void
	saveImage(final Bitmap bitmap,
			  boolean isEightMP) {

		if (null == bitmap)
			return;

		new Thread(() -> {
			Bitmap finalBitmap;
			final File imagePath;

			if (isEightMP) {
				imagePath = mEightMPFile;
				finalBitmap = Bitmap.createScaledBitmap(bitmap,
						IMAGE_WIDTH_4_BY_3_8MP,
						IMAGE_HEIGHT_4_BY_3_8MP,
						false);
			} else {
				imagePath = mFiveMPFile;
				finalBitmap = bitmap;
			}

			String toastMessage;
			if (this.saveImage(finalBitmap, imagePath))
				toastMessage = "Image Saved: " + imagePath.getAbsolutePath();
			else toastMessage = "Unable to save image, please retry...";

			runOnUiThread(() -> Toast.makeText(mContext, toastMessage, LENGTH_SHORT).show());
		}).start();
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	private boolean
	saveImage(Bitmap bitmap,
			  File imagePath) {

		if (null == bitmap || null == imagePath)
			return false;

		if (imagePath.exists())
			imagePath.delete();

		try (OutputStream outputStream = new FileOutputStream(imagePath)) {
			bitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, outputStream);
			outputStream.flush();

			return true;
		} catch (Exception e) {
			Log.e(TAG, "Unable to save image.");
			return false;
		}
	}
}
