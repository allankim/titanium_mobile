/**
 * Appcelerator Titanium Mobile
 * Copyright (c) 2009-2010 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 */
package ti.modules.titanium.ui.widget;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.appcelerator.kroll.KrollDict;
import org.appcelerator.kroll.KrollProxy;
import org.appcelerator.titanium.TiBlob;
import org.appcelerator.titanium.TiC;
import org.appcelerator.titanium.TiContext;
import org.appcelerator.titanium.TiContext.OnLifecycleEvent;
import org.appcelerator.titanium.TiDimension;
import org.appcelerator.titanium.proxy.TiViewProxy;
import org.appcelerator.titanium.util.AsyncResult;
import org.appcelerator.titanium.util.Log;
import org.appcelerator.titanium.util.TiBackgroundImageLoadTask;
import org.appcelerator.titanium.util.TiConfig;
import org.appcelerator.titanium.util.TiConvert;
import org.appcelerator.titanium.util.TiDownloadListener;
import org.appcelerator.titanium.util.TiResponseCache;
import org.appcelerator.titanium.util.TiUIHelper;
import org.appcelerator.titanium.view.TiDrawableReference;
import org.appcelerator.titanium.view.TiUIView;

import ti.modules.titanium.filesystem.FileProxy;
import ti.modules.titanium.ui.ImageViewProxy;
import ti.modules.titanium.ui.widget.TiImageView.OnSizeChangeListener;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.view.ViewParent;
import android.webkit.URLUtil;

public class TiUIImageView extends TiUIView
	implements OnLifecycleEvent, Handler.Callback
{
	private static final String LCAT = "TiUIImageView";
	private static final boolean DBG = TiConfig.LOGD;
	private static final AtomicInteger imageTokenGenerator = new AtomicInteger(0);
	private static final int FRAME_QUEUE_SIZE = 5;
	public static final int INFINITE = 0;

	// TIMOB-3599: A bug in Gingerbread forces us to retry decoding bitmaps when they initially fail
	private static final String PROPERTY_DECODE_RETRIES = "decodeRetries";
	private static final int DEFAULT_DECODE_RETRIES = 5;

	private Timer timer;
	private Animator animator;
	private Object[] images;
	private Loader loader;
	private Thread loaderThread;
	private AtomicBoolean animating = new AtomicBoolean(false);
	private boolean reverse = false;
	private boolean paused = false;
	private int token;
	private boolean firedLoad;
	private ImageViewProxy imageViewProxy;

	private TiDimension requestedWidth;
	private TiDimension requestedHeight;

	private ArrayList<TiDrawableReference> imageSources;
	private TiDrawableReference defaultImageSource;
	private TiDownloadListener downloadListener;
	private int decodeRetries = 0;

	private class BgImageLoader extends TiBackgroundImageLoadTask
	{
		private int token;

		public BgImageLoader(TiContext tiContext, TiDimension imageWidth, TiDimension imageHeight, int token) {
			super(tiContext, getParentView(), imageWidth, imageHeight);
			this.token = token;
		}

		@Override
		protected void onPostExecute(Drawable d) {
			super.onPostExecute(d);

			if (d != null) {
				setImageDrawable(d, token);
			} else {
				if (DBG) {
					String traceMsg = "Background image load returned null";
					if (proxy.hasProperty(TiC.PROPERTY_IMAGE)) {
						Object image = proxy.getProperty(TiC.PROPERTY_IMAGE);
						if (image instanceof String) {
							traceMsg += " (" + TiConvert.toString(image) + ")";
						}
					}
					Log.d(LCAT, traceMsg);
				}
			}
		}
	}

	public TiUIImageView(TiViewProxy proxy)
	{
		super(proxy);
		imageViewProxy = (ImageViewProxy) proxy;

		if (DBG) {
			Log.d(LCAT, "Creating an ImageView");
		}

		TiImageView view = new TiImageView(proxy.getContext());
		view.setOnSizeChangeListener(new OnSizeChangeListener() {
			
			@Override
			public void sizeChanged(int w, int h, int oldWidth, int oldHeight) {
				setImage(true);
			}
		});
		
		downloadListener = new TiDownloadListener() {
			@Override
			public void downloadFinished(URI uri) {
				if (!TiResponseCache.peek(uri)) {
					// The requested image did not make it into our TiResponseCache,
					// possibly because it had a header forbidding that.  Now get it
					// via the "old way" (not relying on cache).
					synchronized (imageTokenGenerator) {
						token = imageTokenGenerator.incrementAndGet();
						imageSources.get(0).getBitmapAsync(new BgImageLoader(getProxy().getTiContext(), requestedWidth, requestedHeight, token));
					}
				} else {
					setImage(true);
				}
			}
		};
		setNativeView(view);
		proxy.getTiContext().addOnLifecycleEventListener(this);
	}

	@Override
	public void setProxy(TiViewProxy proxy)
	{
		super.setProxy(proxy);
		imageViewProxy = (ImageViewProxy) proxy;
	}

	private TiImageView getView()
	{
		return (TiImageView) nativeView;
	}

	protected View getParentView()
	{
		if (nativeView == null) return null;
		ViewParent parent = nativeView.getParent();
		if (parent instanceof View) {
			return (View)parent;
		}
		if (parent == null) {
			TiViewProxy parentProxy = proxy.getParent();
			if (parentProxy != null) {
				TiUIView parentTiUi = parentProxy.peekView();
				if (parentTiUi != null) {
					return parentTiUi.getNativeView();
				}
			}
		}
		return null;
	}
	// This method is intended to only be use from the background task, it's basically
	// an optimistic commit.
	private void setImageDrawable(Drawable d, int token) {
		TiImageView view = getView();
		if (view != null) {
			synchronized(imageTokenGenerator) {
				if (this.token == token) {
					view.setImageDrawable(d, false);
					this.token = -1;
				}
			}
		}
	}
	private Handler handler = new Handler(Looper.getMainLooper(), this);
	private static final int SET_IMAGE = 10001;

	@Override
	public boolean handleMessage(Message msg)
	{
		if (msg.what == SET_IMAGE) {
			AsyncResult result = (AsyncResult)msg.obj;
			TiImageView view = getView();
			if (view != null) {
				view.setImageBitmap((Bitmap)result.getArg());
				result.setResult(null);
			}
		}
		return false;
	}

	private void setImage(final Bitmap bitmap)
	{
		if (bitmap != null) {
			if (!proxy.getTiContext().isUIThread()) {
				AsyncResult result = new AsyncResult(bitmap);
				proxy.sendBlockingUiMessage(handler.obtainMessage(SET_IMAGE, result), result);
			} else {
				TiImageView view = getView();
				if (view != null) {
					view.setImageBitmap(bitmap);
				}
			}
			imageViewProxy.onBitmapChanged(this, bitmap);
		}
	}

	private class BitmapWithIndex
	{
		public BitmapWithIndex(Bitmap b, int i)
		{
			this.bitmap = b;
			this.index = i;
		}

		public Bitmap bitmap;
		public int index;
	}

	private class Loader implements Runnable
	{
		private ArrayBlockingQueue<BitmapWithIndex> bitmapQueue;
		private int repeatIndex = 0;

		public Loader()
		{
			bitmapQueue = new ArrayBlockingQueue<BitmapWithIndex>(FRAME_QUEUE_SIZE);
		}

		private boolean isRepeating()
		{
			int repeatCount = getRepeatCount();
			if (repeatCount <= INFINITE) {
				return true;
			}
			return repeatIndex < repeatCount;
		}

		private int getStart()
		{
			if (imageSources == null) { return 0; }
			if (reverse) { return imageSources.size()-1; }
			return 0;
		}

		private boolean isNotFinalFrame(int frame)
		{
			if (imageSources == null) { return false; }
			if (reverse) { return frame >= 0; }
			return frame < imageSources.size();
		}
		private int getCounter()
		{
			if (reverse) { return -1; }
			return 1;
		}

		public void run()
		{
			if (getProxy() == null) {
				Log.d(LCAT, "Multi-image loader exiting early because proxy has been gc'd");
				return;
			}
			TiContext context = getProxy().getTiContext();
			if (context == null) {
				Log.d(LCAT, "Multi-image loader exiting early because context has been gc'd");
				return;
			}
			repeatIndex = 0;
			animating.set(true);
			firedLoad = false;
			topLoop: while (isRepeating()) {
				if (imageSources == null) { break; }
				long time = System.currentTimeMillis();
				for (int j = getStart(); imageSources != null && isNotFinalFrame(j); j+=getCounter()) {
					if (bitmapQueue.size() == FRAME_QUEUE_SIZE && !firedLoad) {
						fireLoad(TiC.PROPERTY_IMAGES);
						firedLoad = true;
					}
					if (paused && !Thread.currentThread().isInterrupted()) {
						try {
							Log.i(LCAT, "Pausing");
							if (loader == null) { break; } // User backed-out while animation running
							synchronized (loader) {
								loader.wait();
							}
							Log.i(LCAT, "Waking from pause.");
						} catch (InterruptedException e) {
							Log.w(LCAT, "Interrupted from paused state.");
						}
					}
					if (!animating.get()) {
						break topLoop;
					}
					Bitmap b = imageSources.get(j).getBitmap();
					try {
						bitmapQueue.offer(new BitmapWithIndex(b, j), (int)getDuration() * imageSources.size(), TimeUnit.MILLISECONDS);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					repeatIndex++;
				}
				if (DBG) {
					Log.d(LCAT, "TIME TO LOAD FRAMES: "+(System.currentTimeMillis()-time)+"ms");
				}
			}
			animating.set(false);
		}

		public ArrayBlockingQueue<BitmapWithIndex> getBitmapQueue()
		{
			return bitmapQueue;
		}
	}

	private void setImages()
	{
		if (imageSources == null || imageSources.size() == 0) {
			return;
		}
		if (loader == null) {
			paused = false;
			firedLoad = false;
			loader = new Loader();
			Thread loaderThread = new Thread(loader);
			if (DBG) {
				Log.d(LCAT, "STARTING LOADER THREAD "+loaderThread +" for "+this);
			}
			loaderThread.start();
		}
	}

	public double getDuration()
	{
		if (proxy.getProperty(TiC.PROPERTY_DURATION) != null) {
			return TiConvert.toDouble(proxy.getProperty(TiC.PROPERTY_DURATION));
		}

		if (images != null) {
			return images.length * 33;
		}
		return 100;
	}

	public int getRepeatCount() {
		if (proxy.hasProperty(TiC.PROPERTY_REPEAT_COUNT)) {
			return TiConvert.toInt(proxy.getProperty(TiC.PROPERTY_REPEAT_COUNT));
		}
		return INFINITE;
	}

	private void fireLoad(String state)
	{
		KrollDict data = new KrollDict();
		data.put(TiC.EVENT_PROPERTY_STATE, state);
		proxy.fireEvent(TiC.EVENT_LOAD, data);
	}

	private void fireStart()
	{
		KrollDict data = new KrollDict();
		proxy.fireEvent(TiC.EVENT_START, data);
	}

	private void fireChange(int index)
	{
		KrollDict data = new KrollDict();
		data.put(TiC.EVENT_PROPERTY_INDEX, index);
		proxy.fireEvent(TiC.EVENT_CHANGE, data);
	}

	private void fireStop()
	{
		KrollDict data = new KrollDict();
		proxy.fireEvent(TiC.EVENT_STOP, data);
	}

	private class Animator extends TimerTask
	{
		private Loader loader;

		public Animator(Loader loader)
		{
			this.loader = loader;
		}

		public void run()
		{
			try {
				BitmapWithIndex b = loader.getBitmapQueue().take();
				if (DBG) {
					Log.d(LCAT, "set image: "+b.index);
				}
				setImage(b.bitmap);
				fireChange(b.index);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	public void start()
	{
		if (!proxy.getTiContext().isUIThread()) {
			proxy.getTiContext().getActivity().runOnUiThread(new Runnable() {
				public void run() {
					handleStart();
				}
			});
		} else {
			handleStart();
		}
	}

	public void handleStart()
	{
		if (animator == null) {
			timer = new Timer();

			if (loader == null) {
				loader = new Loader();
				loaderThread = new Thread(loader);
				if (DBG) {
					Log.d(LCAT, "STARTING LOADER THREAD "+loaderThread +" for "+this);
				}
			}

			animator = new Animator(loader);
			if (!animating.get()) {
				new Thread(loader).start();
			}

			int duration = (int) getDuration();
			if (duration == 0)
			{
				duration = 1;
			}

			fireStart();
			timer.schedule(animator, duration, duration);
		} else {
			resume();
		}
	}

	public void pause()
	{
		paused = true;
	}

	public void resume()
	{
		paused = false;
		if (loader != null) {
			synchronized (loader) {
				loader.notify();
			}
		}
	}

	public void stop()
	{
		if (timer != null) {
			timer.cancel();
		}
		animating.set(false);

		if (loaderThread != null) {
			loaderThread.interrupt();
			loaderThread = null;
		}
		if (loader != null) {
			synchronized (loader) {
				loader.notify();
			}
		}
		loader = null;
		timer = null;
		animator = null;
		paused = false;

		fireStop();
	}

	private void setImageSource(Object object)
	{
		if (imageViewProxy.inTableView()) {
			ArrayList<TiDrawableReference> currentSources = imageViewProxy.getImageSources();
			if (currentSources != null) {
				imageSources = currentSources;
				return;
			}
		}

		imageSources = new ArrayList<TiDrawableReference>();
		if (object instanceof Object[]) {
			for(Object o : (Object[])object) {
				imageSources.add(makeImageSource(o));
			}
		} else {
			imageSources.add( makeImageSource(object) );
		}
		imageViewProxy.onImageSourcesChanged(this, imageSources);
	}

	private void setImageSource(TiDrawableReference source)
	{
		imageSources = new ArrayList<TiDrawableReference>();
		imageSources.add(source);
	}

	private TiDrawableReference makeImageSource(Object object)
	{
		if (object instanceof FileProxy) {
			return TiDrawableReference.fromFile(getProxy().getTiContext(), ((FileProxy)object).getBaseFile());
		} else {
			return TiDrawableReference.fromObject(getProxy().getTiContext(), object);
		}
	}
	
	private void setDefaultImageSource(Object object)
	{
		if (object instanceof FileProxy) {
			defaultImageSource = TiDrawableReference.fromFile(getProxy().getTiContext(), ((FileProxy)object).getBaseFile());
		} else {
			defaultImageSource = TiDrawableReference.fromObject(getProxy().getTiContext(), object);
		}
	}

	private void setImage(boolean recycle)
	{
		if (imageSources == null || imageSources.size() == 0) {
			setImage(null);
			return;
		}
		if (imageSources.size() == 1) {
			if (imageViewProxy.inTableView()) {
				Bitmap currentBitmap = imageViewProxy.getBitmap();
				if (currentBitmap != null) {
					// If the image proxy has the default image currently cached, we need to
					// load the downloaded URL instead. TIMOB-4814
					ArrayList<TiDrawableReference> proxySources = imageViewProxy.getImageSources();
					if (proxySources != null && !proxySources.contains(defaultImageSource)) {
						setImage(currentBitmap);
						return;
					}
				}
			}
			TiDrawableReference imageref = imageSources.get(0);
			if (imageref.isNetworkUrl()) {
				if (defaultImageSource != null) {
					setDefaultImage();
				} else {
					TiImageView view = getView();
					if (view != null) {
						view.setImageDrawable(null, recycle);
					}
				}
				boolean getAsync = true;
				try {
					URI uri = new URI(imageref.getUrl());
					getAsync = !TiResponseCache.peek(uri);
				} catch (URISyntaxException e) {
					Log.e(LCAT, "URISyntaxException for url " + imageref.getUrl(), e);
					getAsync = false;
				}
				if (getAsync) {
					imageref.getBitmapAsync(downloadListener);
				} else {
					Bitmap bitmap = imageref.getBitmap(getParentView(), requestedWidth, requestedHeight);
					if (bitmap != null) {
						setImage(bitmap);
					} else {
						retryDecode(recycle);
					}
				}
			} else {
				setImage(imageref.getBitmap(getParentView(), requestedWidth, requestedHeight));
			}
		} else {
			setImages();
		}
	}
	
	private void setDefaultImage()
	{
		if (defaultImageSource == null) {
			setImage(null);
			return;
		}
		setImage(defaultImageSource.getBitmap(getParentView(), requestedWidth, requestedHeight));
	}

	private void retryDecode(final boolean recycle)
	{
		// Really odd Android 2.3/Gingerbread behavior -- BitmapFactory.decode* Skia functions
		// fail randomly and seemingly without a cause. Retry 5 times by default w/ 250ms between each try,
		// Usually the 2nd or 3rd try succeeds, but the "decodeRetries" property
		// will allow users to tweak this if needed
		final int maxRetries = proxy.getProperties().optInt(PROPERTY_DECODE_RETRIES, DEFAULT_DECODE_RETRIES);
		if (decodeRetries < maxRetries) {
			decodeRetries++;
			proxy.getUIHandler().postDelayed(new Runnable() {
				public void run() {
					Log.d(LCAT, "Retrying bitmap decode: " + decodeRetries + "/" + maxRetries);
					setImage(recycle);
				}
			}, 250);
		} else {
			String url = null;
			if (imageSources != null && imageSources.size() == 1) {
				url = imageSources.get(0).getUrl();
			}
			Log.e(LCAT, "Max retries reached, giving up decoding image source: " + url);
		}
	}

	@Override
	public void processProperties(KrollDict d)
	{
		TiImageView view = getView();
		if (view == null) {
			return;
		}

		if (d.containsKey(TiC.PROPERTY_WIDTH)) {
			requestedWidth = TiConvert.toTiDimension(d, TiC.PROPERTY_WIDTH, TiDimension.TYPE_WIDTH);
		}
		if (d.containsKey(TiC.PROPERTY_HEIGHT)) {
			requestedHeight = TiConvert.toTiDimension(d, TiC.PROPERTY_HEIGHT, TiDimension.TYPE_HEIGHT);
		}

		if (d.containsKey(TiC.PROPERTY_IMAGES)) {
			setImageSource(d.get(TiC.PROPERTY_IMAGES));
			setImages();
		}
		else if (d.containsKey(TiC.PROPERTY_URL)) {
			Log.w(LCAT, "The url property of ImageView is deprecated, use image instead.");
			if (!d.containsKey(TiC.PROPERTY_IMAGE)) {
				d.put(TiC.PROPERTY_IMAGE, d.get(TiC.PROPERTY_URL));
			}
		}
		if (d.containsKey(TiC.PROPERTY_CAN_SCALE)) {
			view.setCanScaleImage(TiConvert.toBoolean(d, TiC.PROPERTY_CAN_SCALE));
		}
		if (d.containsKey(TiC.PROPERTY_ENABLE_ZOOM_CONTROLS)) {
			view.setEnableZoomControls(TiConvert.toBoolean(d, TiC.PROPERTY_ENABLE_ZOOM_CONTROLS));
		}
		if (d.containsKey(TiC.PROPERTY_DEFAULT_IMAGE)) {
			try {
				if (!d.containsKey(TiC.PROPERTY_IMAGE)
						|| (URLUtil.isNetworkUrl(d.getString(TiC.PROPERTY_IMAGE))
							&& !TiResponseCache.peek(new URI(d.getString(TiC.PROPERTY_IMAGE)))))
					setDefaultImageSource(d.get(TiC.PROPERTY_DEFAULT_IMAGE));
			} catch (URISyntaxException e) {
				setDefaultImageSource(d.get(TiC.PROPERTY_DEFAULT_IMAGE));
			}
		}
		if (d.containsKey(TiC.PROPERTY_IMAGE)) {
			// processProperties is also called from TableView, we need check if we changed before re-creating the bitmap
			boolean changeImage = true;
			Object newImage = d.get(TiC.PROPERTY_IMAGE);
			TiDrawableReference source = makeImageSource(newImage);
			if (imageSources != null && imageSources.size() == 1) {
				if (imageSources.get(0).equals(source)) {
					changeImage = false;
				}
			}
			if (changeImage) {
				setImageSource(source);
				setImage(false);
			}
		} else {
			if (!d.containsKey(TiC.PROPERTY_IMAGES)) {
				getProxy().setProperty(TiC.PROPERTY_IMAGE, null);
				if (defaultImageSource != null) {
					setDefaultImage();
				}
			}
		}
		
		super.processProperties(d);
	}

	
	
	@Override
	public void propertyChanged(String key, Object oldValue, Object newValue, KrollProxy proxy)
	{
		TiImageView view = getView();
		if (view == null) {
			return;
		}
		if (key.equals(TiC.PROPERTY_CAN_SCALE)) {
			view.setCanScaleImage(TiConvert.toBoolean(newValue));
		} else if (key.equals(TiC.PROPERTY_ENABLE_ZOOM_CONTROLS)) {
			view.setEnableZoomControls(TiConvert.toBoolean(newValue));
		} else if (key.equals(TiC.PROPERTY_URL)) {
			setImageSource(newValue);
			setImage(true);
		} else if (key.equals(TiC.PROPERTY_IMAGE)) {
			setImageSource(newValue);
			setImage(true);
		} else if (key.equals(TiC.PROPERTY_IMAGES)) {
			if (newValue instanceof Object[]) {
				setImageSource(newValue);
				setImages();
			}
		} else {
			super.propertyChanged(key, oldValue, newValue, proxy);
		}
	}

	public void onDestroy(Activity activity)
	{
	}

	public void onPause(Activity activity)
	{
		pause();
	}

	public void onResume(Activity activity)
	{
		resume();
	}

	public void onStart(Activity activity)
	{
	}

	public void onStop(Activity activity)
	{
		stop();
	}

	public boolean isAnimating()
	{
		return animating.get() && !paused;
	}

	public boolean isReverse()
	{
		return reverse;
	}

	public void setReverse(boolean reverse) {
		this.reverse = reverse;
	}

	public TiBlob toBlob ()
	{
		TiImageView view = getView();
		if (view != null) {
			Drawable drawable = view.getImageDrawable();
			if (drawable != null && drawable instanceof BitmapDrawable) {
				Bitmap bitmap = ((BitmapDrawable)drawable).getBitmap();
				return TiBlob.blobFromImage(proxy.getTiContext(), bitmap);
			}
		}

		return null;
	}
	
	@Override
	public void setOpacity(float opacity)
	{
		TiImageView view = getView();
		if (view != null) {
			view.setColorFilter(TiUIHelper.createColorFilterForOpacity(opacity));
			super.setOpacity(opacity);
		}
	}

	@Override
	public void clearOpacity(View view)
	{
		super.clearOpacity(view);
		TiImageView iview = getView();
		if (iview != null) {
			iview.setColorFilter(null);
		}
	}

	@Override
	public void release()
	{
		super.release();
		if (loader != null) {
			synchronized (loader) {
				loader.notify();
			}
			loader = null;
		}
		if (imageSources != null) {
			imageSources.clear();
		}
		imageSources = null;
		defaultImageSource = null;
	}
}
