package org.clusterprojector.display;

import android.content.Context;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 零编码镜像渲染器。
 *
 * <p>MediaProjection 将 Display 0 内容输出到 SurfaceTexture；这里用 OpenGL ES
 * 直接把外部纹理缩放/裁剪后渲染到仪表 Display 上的 GLSurfaceView，不经过 H.264/H.265。
 */
public final class MirrorGlSurfaceView extends GLSurfaceView {

    private static final String TAG = "ClusterMirrorGL";

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n"
                    + "attribute vec4 aTextureCoord;\n"
                    + "uniform mat4 uTextureMatrix;\n"
                    + "uniform mat4 uVertexMatrix;\n"
                    + "varying vec2 vTextureCoord;\n"
                    + "void main() {\n"
                    + "  gl_Position = uVertexMatrix * aPosition;\n"
                    + "  vTextureCoord = (uTextureMatrix * aTextureCoord).xy;\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTextureCoord;\n"
                    + "uniform samplerExternalOES uTexture;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(uTexture, vTextureCoord);\n"
                    + "}\n";

    private final RendererImpl renderer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Callback callback;

    public interface Callback {
        void onInputSurfaceReady(Surface surface);
        void onInputSurfaceDestroyed();
        void onFirstFrame();
    }

    public MirrorGlSurfaceView(Context context, int sourceWidth, int sourceHeight) {
        super(context);
        setEGLContextClientVersion(2);
        setZOrderOnTop(true);
        getHolder().setFormat(android.graphics.PixelFormat.OPAQUE);
        renderer = new RendererImpl(sourceWidth, sourceHeight);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        ClusterLog.i(TAG, "ctor: source=" + sourceWidth + "x" + sourceHeight
                + " GLES2 RENDERMODE_WHEN_DIRTY");
    }

    public void setMirrorCallback(Callback value) {
        callback = value;
    }

    @Override protected void onDetachedFromWindow() {
        queueEvent(renderer::release);
        super.onDetachedFromWindow();
    }

    private final class RendererImpl implements Renderer {
        private final int sourceWidth;
        private final int sourceHeight;

        private int program;
        private int textureId = -1;
        private int positionLocation;
        private int textureLocation;
        private int textureMatrixLocation;
        private int vertexMatrixLocation;
        private android.graphics.SurfaceTexture surfaceTexture;
        private Surface inputSurface;
        private final float[] textureMatrix = new float[16];
        private final float[] vertexMatrix = new float[16];
        private final FloatBuffer positionBuffer;
        private final FloatBuffer textureBuffer;

        private int viewportWidth;
        private int viewportHeight;
        private boolean inputReady;
        private long frameAvailableCount;
        private long drawnFrameCount;
        private boolean firstFrameAvailableLogged;
        private boolean firstDrawnFrameLogged;
        private int lastGlError;
        private long lastPeriodicLogMs;

        RendererImpl(int sourceWidth, int sourceHeight) {
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;

            float[] positions = {
                    -1f, -1f,
                     1f, -1f,
                    -1f,  1f,
                     1f,  1f
            };
            float[] textures = {
                    0f, 0f,
                    1f, 0f,
                    0f, 1f,
                    1f, 1f
            };
            positionBuffer = ByteBuffer.allocateDirect(positions.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer().put(positions);
            positionBuffer.position(0);
            textureBuffer = ByteBuffer.allocateDirect(textures.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer().put(textures);
            textureBuffer.position(0);
        }

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            ClusterLog.i(TAG, "GL onSurfaceCreated: vendor=" + GLES20.glGetString(GLES20.GL_VENDOR)
                    + " renderer=" + GLES20.glGetString(GLES20.GL_RENDERER)
                    + " version=" + GLES20.glGetString(GLES20.GL_VERSION));
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
            textureLocation = GLES20.glGetAttribLocation(program, "aTextureCoord");
            textureMatrixLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix");
            vertexMatrixLocation = GLES20.glGetUniformLocation(program, "uVertexMatrix");

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];
            ClusterLog.i(TAG, "GL program=" + program + " texture=" + textureId);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            surfaceTexture = new android.graphics.SurfaceTexture(textureId);
            surfaceTexture.setDefaultBufferSize(sourceWidth, sourceHeight);
            surfaceTexture.setOnFrameAvailableListener(st -> {
                frameAvailableCount++;
                long now = android.os.SystemClock.uptimeMillis();
                if (!firstFrameAvailableLogged) {
                    firstFrameAvailableLogged = true;
                    ClusterLog.i(TAG, "first SurfaceTexture frame available");
                } else if (frameAvailableCount % 300 == 0) {
                    ClusterLog.i(TAG, "frame available count=" + frameAvailableCount
                            + " drawn=" + drawnFrameCount + " glError=0x"
                            + Integer.toHexString(lastGlError));
                    lastPeriodicLogMs = now;
                }
                requestRender();
            });
            inputSurface = new Surface(surfaceTexture);
            inputReady = true;
            ClusterLog.i(TAG, "input Surface ready: valid=" + inputSurface.isValid()
                    + " defaultBuffer=" + sourceWidth + "x" + sourceHeight);

            Surface readySurface = inputSurface;
            mainHandler.post(() -> {
                if (callback != null) callback.onInputSurfaceReady(readySurface);
            });
        }

        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            viewportWidth = width;
            viewportHeight = height;
            GLES20.glViewport(0, 0, width, height);
            ClusterLog.i(TAG, "GL onSurfaceChanged: viewport=" + width + "x" + height
                    + " source=" + sourceWidth + "x" + sourceHeight);
        }

        @Override public void onDrawFrame(GL10 gl) {
            if (!inputReady || surfaceTexture == null) {
                ClusterLog.w(TAG, "onDrawFrame skipped: inputReady=" + inputReady
                        + " texture=" + (surfaceTexture != null));
                return;
            }
            try {
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(textureMatrix);
            android.opengl.Matrix.setIdentityM(vertexMatrix, 0);
            applyContainScale(vertexMatrix);

            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);

            positionBuffer.position(0);
            GLES20.glEnableVertexAttribArray(positionLocation);
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT,
                    false, 0, positionBuffer);

            textureBuffer.position(0);
            GLES20.glEnableVertexAttribArray(textureLocation);
            GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT,
                    false, 0, textureBuffer);

            GLES20.glUniformMatrix4fv(textureMatrixLocation, 1, false, textureMatrix, 0);
            GLES20.glUniformMatrix4fv(vertexMatrixLocation, 1, false, vertexMatrix, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            lastGlError = GLES20.glGetError();
            GLES20.glDisableVertexAttribArray(positionLocation);
            GLES20.glDisableVertexAttribArray(textureLocation);
            drawnFrameCount++;
            if (!firstDrawnFrameLogged) {
                firstDrawnFrameLogged = true;
                ClusterLog.i(TAG, "first frame rendered to dashboard surface: viewport="
                        + viewportWidth + "x" + viewportHeight + " glError=0x"
                        + Integer.toHexString(lastGlError));
                if (callback != null) callback.onFirstFrame();
            }
            } catch (RuntimeException e) {
                ClusterLog.e(TAG, "GL draw failed: frameAvailable=" + frameAvailableCount
                        + " drawn=" + drawnFrameCount, e);
                throw e;
            }
        }

        /** contain 模式：完整保留中控画面，不拉伸，黑边补齐。 */
        private void applyContainScale(float[] matrix) {
            if (viewportWidth <= 0 || viewportHeight <= 0
                    || sourceWidth <= 0 || sourceHeight <= 0) {
                return;
            }
            float viewAspect = viewportWidth / (float) viewportHeight;
            float sourceAspect = sourceWidth / (float) sourceHeight;
            float scaleX = 1f;
            float scaleY = 1f;
            if (viewAspect > sourceAspect) {
                scaleX = sourceAspect / viewAspect;
            } else {
                scaleY = viewAspect / sourceAspect;
            }
            android.opengl.Matrix.scaleM(matrix, 0, scaleX, scaleY, 1f);
        }

        void release() {
            ClusterLog.i(TAG, "release GL: inputReady=" + inputReady
                    + " frameAvailable=" + frameAvailableCount + " drawn=" + drawnFrameCount);
            if (!inputReady) return;
            inputReady = false;
            mainHandler.post(() -> {
                if (callback != null) callback.onInputSurfaceDestroyed();
            });
            try {
                if (inputSurface != null) inputSurface.release();
                if (surfaceTexture != null) surfaceTexture.release();
                if (textureId >= 0) {
                    GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
                }
            } catch (Throwable t) {
                ClusterLog.w(TAG, "release mirror GL resources failed", t);
            }
            inputSurface = null;
            surfaceTexture = null;
            textureId = -1;
        }
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            ClusterLog.e(TAG, "GL shader compile failed type=" + type + " log=" + log);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("GL shader compile failed: " + log);
        }
        return shader;
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            ClusterLog.e(TAG, "GL program link failed log=" + log);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("GL program link failed: " + log);
        }
        return program;
    }
}
