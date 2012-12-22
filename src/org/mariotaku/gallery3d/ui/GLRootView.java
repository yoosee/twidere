/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mariotaku.gallery3d.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.opengles.GL11;

import org.mariotaku.gallery3d.anim.CanvasAnimation;
import org.mariotaku.gallery3d.common.ApiHelper;
import org.mariotaku.gallery3d.common.Utils;
import org.mariotaku.gallery3d.util.GalleryUtils;
import org.mariotaku.gallery3d.util.MotionEventHelper;
import org.mariotaku.twidere.R;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Process;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;

// The root component of all <code>GLView</code>s. The rendering is done in GL
// thread while the event handling is done in the main thread.  To synchronize
// the two threads, the entry points of this package need to synchronize on the
// <code>GLRootView</code> instance unless it can be proved that the rendering
// thread won't access the same thing as the method. The entry points include:
// (1) The public methods of HeadUpDisplay
// (2) The public methods of CameraHeadUpDisplay
// (3) The overridden methods in GLRootView.
public class GLRootView extends GLSurfaceView implements GLSurfaceView.Renderer, GLRoot {
	private static final String TAG = "GLRootView";

	private static final boolean DEBUG_FPS = false;
	private int mFrameCount = 0;
	private long mFrameCountingStart = 0;

	private static final boolean DEBUG_INVALIDATE = false;
	private int mInvalidateColor = 0;

	private static final boolean DEBUG_DRAWING_STAT = false;

	private static final boolean false_SLOW_ONLY = false;

	private static final int FLAG_INITIALIZED = 1;
	private static final int FLAG_NEED_LAYOUT = 2;

	private GL11 mGL;
	private GLCanvas mCanvas;
	private GLView mContentView;

	private OrientationSource mOrientationSource;
	// mCompensation is the difference between the UI orientation on GLCanvas
	// and the framework orientation. See OrientationManager for details.
	private int mCompensation;
	// mCompensationMatrix maps the coordinates of touch events. It is kept sync
	// with mCompensation.
	private final Matrix mCompensationMatrix = new Matrix();
	private int mDisplayRotation;

	private int mFlags = FLAG_NEED_LAYOUT;
	private volatile boolean mRenderRequested = false;

	private final GalleryEGLConfigChooser mEglConfigChooser = new GalleryEGLConfigChooser();

	private final ArrayList<CanvasAnimation> mAnimations = new ArrayList<CanvasAnimation>();

	private final ArrayDeque<OnGLIdleListener> mIdleListeners = new ArrayDeque<OnGLIdleListener>();

	private final IdleRunner mIdleRunner = new IdleRunner();

	private final ReentrantLock mRenderLock = new ReentrantLock();
	private final Condition mFreezeCondition = mRenderLock.newCondition();
	private boolean mFreeze;

	private long mLastDrawFinishTime;
	private boolean mInDownState = false;
	private boolean mFirstDraw = true;

	private final Runnable mRequestRenderOnAnimationFrame = new Runnable() {
		@Override
		public void run() {
			superRequestRender();
		}
	};

	public GLRootView(final Context context) {
		this(context, null);
	}

	public GLRootView(final Context context, final AttributeSet attrs) {
		super(context, attrs);
		mFlags |= FLAG_INITIALIZED;
		setBackgroundDrawable(null);
		setEGLConfigChooser(mEglConfigChooser);
		setRenderer(this);
		if (ApiHelper.USE_888_PIXEL_FORMAT) {
			getHolder().setFormat(PixelFormat.RGB_888);
		} else {
			getHolder().setFormat(PixelFormat.RGB_565);
		}

		// Uncomment this to enable gl error check.
		// setDebugFlags(DEBUG_CHECK_GL_ERROR);
	}

	@Override
	public void addOnGLIdleListener(final OnGLIdleListener listener) {
		synchronized (mIdleListeners) {
			mIdleListeners.addLast(listener);
			mIdleRunner.enable();
		}
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent event) {
		if (!isEnabled()) return false;

		final int action = event.getAction();
		if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
			mInDownState = false;
		} else if (!mInDownState && action != MotionEvent.ACTION_DOWN) return false;

		if (mCompensation != 0) {
			event = MotionEventHelper.transformEvent(event, mCompensationMatrix);
		}

		mRenderLock.lock();
		try {
			// If this has been detached from root, we don't need to handle
			// event
			final boolean handled = mContentView != null && mContentView.dispatchTouchEvent(event);
			if (action == MotionEvent.ACTION_DOWN && handled) {
				mInDownState = true;
			}
			return handled;
		} finally {
			mRenderLock.unlock();
		}
	}

	@Override
	public void freeze() {
		mRenderLock.lock();
		mFreeze = true;
		mRenderLock.unlock();
	}

	@Override
	public int getCompensation() {
		return mCompensation;
	}

	@Override
	public Matrix getCompensationMatrix() {
		return mCompensationMatrix;
	}

	@Override
	public int getDisplayRotation() {
		return mDisplayRotation;
	}

	@Override
	public void lockRenderThread() {
		mRenderLock.lock();
	}

	@Override
	public void onDrawFrame(final GL10 gl) {
		AnimationTime.update();
		long t0;
		if (false_SLOW_ONLY) {
			t0 = System.nanoTime();
		}
		mRenderLock.lock();

		while (mFreeze) {
			mFreezeCondition.awaitUninterruptibly();
		}

		try {
			onDrawFrameLocked(gl);
		} finally {
			mRenderLock.unlock();
		}

		// We put a black cover View in front of the SurfaceView and hide it
		// after the first draw. This prevents the SurfaceView being transparent
		// before the first draw.
		if (mFirstDraw) {
			mFirstDraw = false;
			post(new Runnable() {
				@Override
				public void run() {
					final View root = getRootView();
					final View cover = root.findViewById(R.id.gl_root_cover);
					cover.setVisibility(GONE);
				}
			});
		}

		if (false_SLOW_ONLY) {
			final long t = System.nanoTime();
			final long durationInMs = (t - mLastDrawFinishTime) / 1000000;
			final long durationDrawInMs = (t - t0) / 1000000;
			mLastDrawFinishTime = t;

			if (durationInMs > 34) { // 34ms -> we skipped at least 2 frames
				Log.v(TAG, "----- SLOW (" + durationDrawInMs + "/" + durationInMs + ") -----");
			}
		}
	}

	@Override
	public void onPause() {
		unfreeze();
		super.onPause();
	}

	/**
	 * Called when the OpenGL surface is recreated without destroying the
	 * context.
	 */
	// This is a GLSurfaceView.Renderer callback
	@Override
	public void onSurfaceChanged(final GL10 gl1, final int width, final int height) {
		Log.i(TAG, "onSurfaceChanged: " + width + "x" + height + ", gl10: " + gl1.toString());
		Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
		GalleryUtils.setRenderThread();
		final GL11 gl = (GL11) gl1;
		Utils.assertTrue(mGL == gl);

		mCanvas.setSize(width, height);
	}

	/**
	 * Called when the context is created, possibly after automatic destruction.
	 */
	// This is a GLSurfaceView.Renderer callback
	@Override
	public void onSurfaceCreated(final GL10 gl1, final EGLConfig config) {
		final GL11 gl = (GL11) gl1;
		if (mGL != null) {
			// The GL Object has changed
			Log.i(TAG, "GLObject has changed from " + mGL + " to " + gl);
		}
		mRenderLock.lock();
		try {
			mGL = gl;
			mCanvas = new GLCanvasImpl(gl);
			BasicTexture.invalidateAllTextures();
		} finally {
			mRenderLock.unlock();
		}

		if (DEBUG_FPS) {
			setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
		} else {
			setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
		}
	}

	@Override
	public void registerLaunchedAnimation(final CanvasAnimation animation) {
		// Register the newly launched animation so that we can set the start
		// time more precisely. (Usually, it takes much longer for first
		// rendering, so we set the animation start time as the time we
		// complete rendering)
		mAnimations.add(animation);
	}

	@Override
	public void requestLayoutContentPane() {
		mRenderLock.lock();
		try {
			if (mContentView == null || (mFlags & FLAG_NEED_LAYOUT) != 0) return;

			// "View" system will invoke onLayout() for initialization(bug ?),
			// we
			// have to ignore it since the GLThread is not ready yet.
			if ((mFlags & FLAG_INITIALIZED) == 0) return;

			mFlags |= FLAG_NEED_LAYOUT;
			requestRender();
		} finally {
			mRenderLock.unlock();
		}
	}

	@Override
	public void requestRender() {
		if (DEBUG_INVALIDATE) {
			final StackTraceElement e = Thread.currentThread().getStackTrace()[4];
			final String caller = e.getFileName() + ":" + e.getLineNumber() + " ";
			Log.d(TAG, "invalidate: " + caller);
		}
		if (mRenderRequested) return;
		mRenderRequested = true;
		super.requestRender();
	}

	@Override
	public void requestRenderForced() {
		superRequestRender();
	}

	@Override
	public void setContentPane(final GLView content) {
		if (mContentView == content) return;
		if (mContentView != null) {
			if (mInDownState) {
				final long now = SystemClock.uptimeMillis();
				final MotionEvent cancelEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
				mContentView.dispatchTouchEvent(cancelEvent);
				cancelEvent.recycle();
				mInDownState = false;
			}
			mContentView.detachFromRoot();
			BasicTexture.yieldAllTextures();
		}
		mContentView = content;
		if (content != null) {
			content.attachToRoot(this);
			requestLayoutContentPane();
		}
	}

	@Override
	public void setOrientationSource(final OrientationSource source) {
		mOrientationSource = source;
	}

	// We need to unfreeze in the following methods and in onPause().
	// These methods will wait on GLThread. If we have freezed the GLRootView,
	// the GLThread will wait on main thread to call unfreeze and cause dead
	// lock.
	@Override
	public void surfaceChanged(final SurfaceHolder holder, final int format, final int w, final int h) {
		unfreeze();
		super.surfaceChanged(holder, format, w, h);
	}

	@Override
	public void surfaceCreated(final SurfaceHolder holder) {
		unfreeze();
		super.surfaceCreated(holder);
	}

	@Override
	public void surfaceDestroyed(final SurfaceHolder holder) {
		unfreeze();
		super.surfaceDestroyed(holder);
	}

	@Override
	public void unfreeze() {
		mRenderLock.lock();
		mFreeze = false;
		mFreezeCondition.signalAll();
		mRenderLock.unlock();
	}

	@Override
	public void unlockRenderThread() {
		mRenderLock.unlock();
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			unfreeze();
		} finally {
			super.finalize();
		}
	}

	@Override
	protected void onDetachedFromWindow() {
		unfreeze();
		super.onDetachedFromWindow();
	}

	@Override
	protected void onLayout(final boolean changed, final int left, final int top, final int right, final int bottom) {
		if (changed) {
			requestLayoutContentPane();
		}
	}

	private void layoutContentPane() {
		mFlags &= ~FLAG_NEED_LAYOUT;

		int w = getWidth();
		int h = getHeight();
		int displayRotation = 0;
		int compensation = 0;

		// Get the new orientation values
		if (mOrientationSource != null) {
			displayRotation = mOrientationSource.getDisplayRotation();
			compensation = mOrientationSource.getCompensation();
		} else {
			displayRotation = 0;
			compensation = 0;
		}

		if (mCompensation != compensation) {
			mCompensation = compensation;
			if (mCompensation % 180 != 0) {
				mCompensationMatrix.setRotate(mCompensation);
				// move center to origin before rotation
				mCompensationMatrix.preTranslate(-w / 2, -h / 2);
				// align with the new origin after rotation
				mCompensationMatrix.postTranslate(h / 2, w / 2);
			} else {
				mCompensationMatrix.setRotate(mCompensation, w / 2, h / 2);
			}
		}
		mDisplayRotation = displayRotation;

		// Do the actual layout.
		if (mCompensation % 180 != 0) {
			final int tmp = w;
			w = h;
			h = tmp;
		}
		Log.i(TAG, "layout content pane " + w + "x" + h + " (compensation " + mCompensation + ")");
		if (mContentView != null && w != 0 && h != 0) {
			mContentView.layout(0, 0, w, h);
		}
		// Uncomment this to dump the view hierarchy.
		// mContentView.dumpTree("");
	}

	private void onDrawFrameLocked(final GL10 gl) {
		if (DEBUG_FPS) {
			outputFps();
		}

		// release the unbound textures and deleted buffers.
		mCanvas.deleteRecycledResources();

		// reset texture upload limit
		UploadedTexture.resetUploadLimit();

		mRenderRequested = false;

		if ((mFlags & FLAG_NEED_LAYOUT) != 0) {
			layoutContentPane();
		}

		mCanvas.save(GLCanvas.SAVE_FLAG_ALL);
		rotateCanvas(-mCompensation);
		if (mContentView != null) {
			mContentView.render(mCanvas);
		}
		mCanvas.restore();

		if (!mAnimations.isEmpty()) {
			final long now = AnimationTime.get();
			for (int i = 0, n = mAnimations.size(); i < n; i++) {
				mAnimations.get(i).setStartTime(now);
			}
			mAnimations.clear();
		}

		if (UploadedTexture.uploadLimitReached()) {
			requestRender();
		}

		synchronized (mIdleListeners) {
			if (!mIdleListeners.isEmpty()) {
				mIdleRunner.enable();
			}
		}

		if (DEBUG_INVALIDATE) {
			mCanvas.fillRect(10, 10, 5, 5, mInvalidateColor);
			mInvalidateColor = ~mInvalidateColor;
		}

		if (DEBUG_DRAWING_STAT) {
			mCanvas.dumpStatisticsAndClear();
		}
	}

	private void outputFps() {
		final long now = System.nanoTime();
		if (mFrameCountingStart == 0) {
			mFrameCountingStart = now;
		} else if (now - mFrameCountingStart > 1000000000) {
			Log.d(TAG, "fps: " + (double) mFrameCount * 1000000000 / (now - mFrameCountingStart));
			mFrameCountingStart = now;
			mFrameCount = 0;
		}
		++mFrameCount;
	}

	private void rotateCanvas(final int degrees) {
		if (degrees == 0) return;
		final int w = getWidth();
		final int h = getHeight();
		final int cx = w / 2;
		final int cy = h / 2;
		mCanvas.translate(cx, cy);
		mCanvas.rotate(degrees, 0, 0, 1);
		if (degrees % 180 != 0) {
			mCanvas.translate(-cy, -cx);
		} else {
			mCanvas.translate(-cx, -cy);
		}
	}

	private void superRequestRender() {
		super.requestRender();
	}

	private class IdleRunner implements Runnable {
		// true if the idle runner is in the queue
		private boolean mActive = false;

		public void enable() {
			// Who gets the flag can add it to the queue
			if (mActive) return;
			mActive = true;
			queueEvent(this);
		}

		@Override
		public void run() {
			OnGLIdleListener listener;
			synchronized (mIdleListeners) {
				mActive = false;
				if (mIdleListeners.isEmpty()) return;
				listener = mIdleListeners.removeFirst();
			}
			mRenderLock.lock();
			boolean keepInQueue;
			try {
				keepInQueue = listener.onGLIdle(mCanvas, mRenderRequested);
			} finally {
				mRenderLock.unlock();
			}
			synchronized (mIdleListeners) {
				if (keepInQueue) {
					mIdleListeners.addLast(listener);
				}
				if (!mRenderRequested && !mIdleListeners.isEmpty()) {
					enable();
				}
			}
		}
	}
}
