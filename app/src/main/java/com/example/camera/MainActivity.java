package com.example.camera;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;

import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;


import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity {

    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceViewHolder;
    private Handler mHandler;
    private ImageReader mImageReader;
    private CameraDevice mCameraDevice;
    private CaptureRequest.Builder mPreviewBuilder;
    private CameraCaptureSession mSession;
    private int mDeviceRotation;
    private Sensor mAccelerometer;
    private Sensor mMagnetometer;
    private SensorManager mSensorManager;
    private DeviceOrientation deviceOrientation;
    int mDSI_height, mDSI_width;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(ExifInterface.ORIENTATION_NORMAL, 0);
        ORIENTATIONS.append(ExifInterface.ORIENTATION_ROTATE_90, 90);
        ORIENTATIONS.append(ExifInterface.ORIENTATION_ROTATE_180, 180);
        ORIENTATIONS.append(ExifInterface.ORIENTATION_ROTATE_270, 270);
    }

    Button gallery;

    @Override
    public void onPerformDirectAction(@NonNull String actionId, @NonNull Bundle arguments, @NonNull CancellationSignal cancellationSignal, @NonNull Consumer<Bundle> resultListener) {
        super.onPerformDirectAction(actionId, arguments, cancellationSignal, resultListener);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 상태바를 안보이도록 합니다.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // 화면 켜진 상태를 유지합니다.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);


        ImageButton button = findViewById(R.id.take_photo);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                takePicture();
            }
        });

        gallery = (Button)findViewById(R.id.gotoGall);
        gallery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent mIntent = new Intent(Intent.ACTION_VIEW,Uri.parse("content://media/internal/images/media"));
                startActivity(mIntent);

            }
        });

        mSurfaceView = findViewById(R.id.surfaceView);
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mMagnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        deviceOrientation = new DeviceOrientation();

        initSurfaceView();

    }

    @Override
    protected void onResume() {
        super.onResume();

        mSensorManager.registerListener(deviceOrientation.getEventListener(), mAccelerometer, SensorManager.SENSOR_DELAY_UI);
        mSensorManager.registerListener(deviceOrientation.getEventListener(), mMagnetometer, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onPause() {
        super.onPause();

        mSensorManager.unregisterListener(deviceOrientation.getEventListener());
    }

    public void initSurfaceView() {

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        //스마트폰 크기 호환성을 위해 개발자 코드에서 직접 스마트폰의 크기정보를 획득해야 하는 경우
        //가로세로 화소수, 화면밀도, 정확한 가로세로 밀도 등의 정보를 획득할 수 있다.
        mDSI_height = displayMetrics.heightPixels; //세로 화소수
        mDSI_width = displayMetrics.widthPixels; //가로 화소수


        mSurfaceViewHolder = mSurfaceView.getHolder();//getholder 메소드를 통해 surfaceVeiws의 surfaceHolder 인스턴스를 연결
        mSurfaceViewHolder.addCallback(new SurfaceHolder.Callback() {

            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                initCameraAndPreview();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {

                if (mCameraDevice != null) {
                    mCameraDevice.close();
                    mCameraDevice = null;
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

            }

        });
    }


    @TargetApi(19)
    public void initCameraAndPreview() {
        HandlerThread handlerThread = new HandlerThread("CAMERA2");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        Handler mainHandler = new Handler(getMainLooper());
        try {
            String mCameraId = "" + CameraCharacteristics.LENS_FACING_FRONT; // 후면 카메라 사용

            CameraManager mCameraManager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);
            //스마트폰에 탑재된 카메라의 식별자를 얻음.
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);//카메라의 각종 지원정보 포함
            //StreamConfigurationMap에너 얻는 가장 중요한 정보는 카메라에서 지원하는 크기목록이다.
            //getoutsize()함수에 이미지 포맷을 매개변수로 지정하면 크기목록이 size객체의 배열로 반환되는데,
            //이 값을 이용하여 사진 촬영시 사지니 크기를 지정할 수 있다.
            Size largestPreviewSize = map.getOutputSizes(ImageFormat.JPEG)[0];
            Log.i("LargestSize", largestPreviewSize.getWidth() + " " + largestPreviewSize.getHeight());

            setAspectRatioTextureView(largestPreviewSize.getHeight(),largestPreviewSize.getWidth());

            mImageReader = ImageReader.newInstance(largestPreviewSize.getWidth(), largestPreviewSize.getHeight(), ImageFormat.JPEG,/*maxImages*/7);
            //이미지 객체를 만듦. 여기서 maximages, format지정
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mainHandler);//imageReader가 새로운 이미지를 사용가능할 때 호출될 리스너를 등록
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            mCameraManager.openCamera(mCameraId, deviceStateCallback, mHandler);
        } catch (CameraAccessException e) {
            Toast.makeText(this, "카메라를 열지 못했습니다.", Toast.LENGTH_SHORT).show();
        }
    }


    private ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {

            Image image = reader.acquireNextImage();//ImageReader 큐의 다음 이미지를 가져옴.
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            final Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            new SaveImageTask().execute(bitmap);
        }
    };


    private CameraDevice.StateCallback deviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;
            try {
                takePreview();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Toast.makeText(MainActivity.this, "카메라를 열지 못했습니다.", Toast.LENGTH_SHORT).show();
        }
    };


    public void takePreview() throws CameraAccessException {
        mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        mPreviewBuilder.addTarget(mSurfaceViewHolder.getSurface());
        mCameraDevice.createCaptureSession(Arrays.asList(mSurfaceViewHolder.getSurface(), mImageReader.getSurface()), mSessionPreviewStateCallback, mHandler);
    }

    private CameraCaptureSession.StateCallback mSessionPreviewStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            mSession = session;

            try {

                mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                mSession.setRepeatingRequest(mPreviewBuilder.build(), null, mHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Toast.makeText(MainActivity.this, "카메라 구성 실패", Toast.LENGTH_SHORT).show();
        }
    };

    private CameraCaptureSession.CaptureCallback mSessionCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            mSession = session;
            unlockFocus();
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            mSession = session;
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
        }
    };


    public void takePicture() {

        try {
            CaptureRequest.Builder captureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);//用来设置拍照请求的request
            captureRequestBuilder.addTarget(mImageReader.getSurface());
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);


            // 화면 회전 안되게 고정시켜 놓은 상태에서는 아래 로직으로 방향을 얻을 수 없어서
            // 센서를 사용하는 것으로 변경
            //deviceRotation = getResources().getConfiguration().orientation;
            mDeviceRotation = ORIENTATIONS.get(deviceOrientation.getOrientation());
            Log.d("@@@", mDeviceRotation+"");

            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, mDeviceRotation);
            CaptureRequest mCaptureRequest = captureRequestBuilder.build();
            mSession.capture(mCaptureRequest, mSessionCaptureCallback, mHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public Bitmap getRotatedBitmap(Bitmap bitmap, int degrees) throws Exception {
        if(bitmap == null) return null;
        if (degrees == 0) return bitmap;

        Matrix m = new Matrix();
        m.setRotate(degrees, (float) bitmap.getWidth() / 2, (float) bitmap.getHeight() / 2);

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
    }


    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private void unlockFocus() {
        try {
            // Reset the auto-focus trigger
            mPreviewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            mSession.capture(mPreviewBuilder.build(), mSessionCaptureCallback,
                    mHandler);
            // After this, the camera will go back to the normal state of preview.
            mSession.setRepeatingRequest(mPreviewBuilder.build(), mSessionCaptureCallback,
                    mHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    //출처 - https://codeday.me/ko/qa/20190310/39556.html
    /**
     * A copy of the Android internals  insertImage method, this method populates the
     * meta data with DATE_ADDED and DATE_TAKEN. This fixes a common problem where media
     * that is inserted manually gets saved at the end of the gallery (because date is not populated).
     * @see android.provider.MediaStore.Images.Media#insertImage(ContentResolver, Bitmap, String, String)
     */
    public static final String insertImage(ContentResolver cr,
                                           Bitmap source,
                                           String title,
                                           String description) {

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.TITLE, title);
        values.put(MediaStore.Images.Media.DISPLAY_NAME, title);
        values.put(MediaStore.Images.Media.DESCRIPTION, description);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        // Add the date meta data to ensure the image is added at the front of the gallery
        values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis());
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());

        Uri url = null;
        String stringUrl = null;    /* value to be returned */

        try {
            url = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (source != null) {
                OutputStream imageOut = cr.openOutputStream(url);
                try {
                    source.compress(Bitmap.CompressFormat.JPEG, 50, imageOut);
                } finally {
                    imageOut.close();
                }

            } else {
                cr.delete(url, null, null);
                url = null;
            }
        } catch (Exception e) {
            if (url != null) {
                cr.delete(url, null, null);
                url = null;
            }
        }

        if (url != null) {
            stringUrl = url.toString();
        }

        return stringUrl;
    }


    private class SaveImageTask extends AsyncTask<Bitmap, Void, Void> {

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);

            Toast.makeText(MainActivity.this, "사진을 저장하였습니다.", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected Void doInBackground(Bitmap... data) {

            Bitmap bitmap = null;
            try {
                bitmap = getRotatedBitmap(data[0], mDeviceRotation);
            } catch (Exception e) {
                e.printStackTrace();
            }
            insertImage(getContentResolver(), bitmap, ""+System.currentTimeMillis(), "");

            return null;
        }

    }


    // 출처 https://stackoverflow.com/a/43516672
    private void setAspectRatioTextureView(int ResolutionWidth , int ResolutionHeight )
    {
        if(ResolutionWidth > ResolutionHeight){
            int newWidth = mDSI_width;
            int newHeight = ((mDSI_width * ResolutionWidth)/ResolutionHeight);
            updateTextureViewSize(newWidth,newHeight);

        }else {
            int newWidth = mDSI_width;
            int newHeight = ((mDSI_width * ResolutionHeight)/ResolutionWidth);
            updateTextureViewSize(newWidth,newHeight);
        }

    }

    private void updateTextureViewSize(int viewWidth, int viewHeight) {
        Log.d("@@@", "TextureView Width : " + viewWidth + " TextureView Height : " + viewHeight);
        mSurfaceView.setLayoutParams(new FrameLayout.LayoutParams(viewWidth, viewHeight));
    }


}
