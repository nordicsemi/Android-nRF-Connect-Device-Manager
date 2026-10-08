package no.nordicsemi.android.mcumgr.sample.graph;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

import no.nordicsemi.android.mcumgr.sample.R;
import no.nordicsemi.kotlin.ble.core.Phy;

@SuppressWarnings("unused")
public class ThroughputGraph extends View {
	private static final float DEFAULT_MAX_THROUGHPUT = 40.0f;

	private final Paint parametersPaint;
	private final Paint averageThroughputPaint;
	private final Paint averageThroughputFillPaint;
	private final Paint horizontalLinesPaint;
	private float tenKbPerSHeight;
	/** View dimension. */
	private int width, height;
	private boolean showMetadata;

	private final float[] averageThroughputData = new float[101];
	private final float[] connectionIntervalData = new float[101];
	private final int[] progressData = new int[101];

	private float currentMaxThroughput, maxThroughput, currentConnectionInterval;
	private int mtu, bufferSize;
	private Phy txPhy, rxPhy;
	private int currentIndex;
	private final Path throughputPath = new Path();
	private final float[] averageThroughputPoints = new float[4 * 101];

	private final float averageThroughputTextWidth;

	public ThroughputGraph(final Context context, @Nullable final AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public ThroughputGraph(final Context context, @Nullable final AttributeSet attrs, final int defStyleAttr) {
		this(context, attrs, defStyleAttr, 0);
	}

	public ThroughputGraph(final Context context, @Nullable final AttributeSet attrs, final int defStyleAttr, final int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);

		currentMaxThroughput = maxThroughput = DEFAULT_MAX_THROUGHPUT; // kB/s

		horizontalLinesPaint = new Paint();
		horizontalLinesPaint.setStrokeWidth(2);
		horizontalLinesPaint.setColor(ContextCompat.getColor(context, R.color.colorGraphGrid));

		parametersPaint = new Paint();
		parametersPaint.setStrokeWidth(2);
		parametersPaint.setTextSize(32.0f);
		parametersPaint.setColor(ContextCompat.getColor(context, R.color.colorParams));

		averageThroughputPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		averageThroughputPaint.setStrokeWidth(5);
		averageThroughputPaint.setStrokeJoin(Paint.Join.ROUND);
		averageThroughputPaint.setTextSize(32.0f);
		averageThroughputPaint.setTypeface(Typeface.DEFAULT_BOLD);
		averageThroughputPaint.setColor(ContextCompat.getColor(context, R.color.colorAverageThroughput));

		averageThroughputFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		averageThroughputFillPaint.setStyle(Paint.Style.FILL);
		averageThroughputFillPaint.setColor(ContextCompat.getColor(context, R.color.colorInstantaneousThroughput));

		averageThroughputTextWidth = averageThroughputPaint.measureText("XX.X kB/s");

		setOnClickListener(v -> {
			showMetadata = !showMetadata;
			invalidate();
		});
	}

	// State restoration ---------------------------------------------------------------------------

	@Override
	protected void onRestoreInstanceState(final Parcelable state) {
		final SavedState ss = (SavedState) state;

		super.onRestoreInstanceState(ss.getSuperState());

		currentMaxThroughput = ss.maxThroughput;
		currentIndex = ss.currentPercent;
		currentConnectionInterval = ss.currentConnectionInterval;
		mtu = ss.mtu;
		bufferSize = ss.bufferSize;
		txPhy = ss.txPhy;
		rxPhy = ss.rxPhy;
		showMetadata = ss.showMetadata;
		System.arraycopy(ss.averageThroughputData, 0, averageThroughputData, 0, averageThroughputData.length);
		System.arraycopy(ss.connectionIntervalData, 0, connectionIntervalData, 0, connectionIntervalData.length);
		System.arraycopy(ss.progressData, 0, progressData, 0, progressData.length);
	}

	@Nullable
	@Override
	protected Parcelable onSaveInstanceState() {
		final Parcelable superState = super.onSaveInstanceState();
		final SavedState state = new SavedState(superState);
		state.maxThroughput = currentMaxThroughput;
		state.averageThroughputData = averageThroughputData;
		state.connectionIntervalData = connectionIntervalData;
		state.currentConnectionInterval = currentConnectionInterval;
		state.mtu = mtu;
		state.bufferSize = bufferSize;
		state.txPhy = txPhy;
		state.rxPhy = rxPhy;
		state.progressData = progressData;
		state.currentPercent = currentIndex;
		state.showMetadata = showMetadata;
		return state;
	}

	// Drawing -------------------------------------------------------------------------------------

	@Override
	protected void onDraw(@NotNull final Canvas canvas) {
		super.onDraw(canvas);

		if (currentIndex > 0) {
			// First, draw the throughput path.
			canvas.drawPath(throughputPath, averageThroughputFillPaint);

			// Draw the horizontal lines indicating each 10 kB/s.
			for (float h = height; h > 0; h -= tenKbPerSHeight) {
				canvas.drawLine(0, h, width, h, horizontalLinesPaint);
			}

			// Draw the average throughput.
			canvas.drawLines(averageThroughputPoints, 0, currentIndex << 2, averageThroughputPaint);

			// And print the average throughput value.
			final String text = getResources().getString(R.string.image_upgrade_speed, averageThroughputData[currentIndex - 1]);
			final float x = averageThroughputPoints[(currentIndex << 2) - 2] - averageThroughputTextWidth;
			final float y = averageThroughputPoints[(currentIndex << 2) - 1] - 2 * averageThroughputPaint.getStrokeWidth();
			canvas.drawText(text, Math.max(0, x), Math.max(0, y), averageThroughputPaint);

			// Draw optional metadata.
			// This is only for Android 8+, where the connection interval is available.
			if (showMetadata && currentConnectionInterval > 0) {
				float lastConnectionInterval = 0;
				for (int i = 0; i < currentIndex; ++i) {
					final float connectionInterval = connectionIntervalData[i];

					if (lastConnectionInterval != connectionInterval) {
						final float px = averageThroughputPoints[(i << 2)];
						final float py = averageThroughputPoints[(i << 2) + 1];
						canvas.drawLine(px, py, px, height, parametersPaint);

						float offset = 2 * parametersPaint.getStrokeWidth();
						final String metadata = bufferSize > mtu  ?
								getResources().getString(R.string.image_upgrade_ci_sar, mtu, bufferSize, getPhyAsString(), connectionInterval) :
								getResources().getString(R.string.image_upgrade_ci, mtu, getPhyAsString(), connectionInterval);
						final String[] parts = metadata.split("\n");
						for (final String part : parts) {
							canvas.drawText(
									part,
									px + 2 * parametersPaint.getStrokeWidth(),
									height - offset,
									parametersPaint
							);
							offset += parametersPaint.getTextSize();
						}
					}

					lastConnectionInterval = connectionInterval;
				}
			}
		}
	}

	private String getPhyAsString() {
		if (txPhy == rxPhy) {
			return txPhy.toString();
		}
		return txPhy.toString() + " / " + rxPhy.toString();
	}

	@Override
	protected void onSizeChanged(final int w, final int h, final int oldw, final int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		width = w;
		height = h;
		recalculate();
		recalculateMetadata();
	}

	// Public API ----------------------------------------------------------------------------------

	/**
	 * Sets the max throughput indicator at the given level.
	 * @param maxThroughput the value considered as high. A line will be drown there.
	 */
	public void setMaxThroughput(final float maxThroughput) {
		this.maxThroughput = Math.max(0, maxThroughput);
		if (maxThroughput > currentMaxThroughput)
			currentMaxThroughput = maxThroughput;
		recalculate();
		recalculateMetadata();
	}

	/**
	 * Adds a new throughput point to the graph.
	 *
	 * @param progress The current upload percentage for the measured throughout, from 0 to 100.
	 * @param averageThroughput The average throughput in kB/s.
	 */
	public void addProgress(final int progress, final float averageThroughput) {
		if (currentIndex < averageThroughputData.length && progressData[currentIndex] == 0) {
			averageThroughputData[currentIndex] = averageThroughput;
			connectionIntervalData[currentIndex] = currentConnectionInterval;
			progressData[currentIndex] = progress;
			currentIndex += 1;

			if (currentMaxThroughput < averageThroughput * 1.2f) {
				currentMaxThroughput = averageThroughput * 1.2f;
				recalculateMetadata();
			}
			recalculate();
		}
	}

	/**
	 * Sets the new connection interval.
	 *
	 * @param interval the connection interval, in milliseconds.
	 * @param mtu current MTU.
	 * @param bufferSize maximum McuMgr buffer size.
	 * @param txPhy	current TX PHY used.
	 * @param rxPhy	current RX PHY used.
	 */
	public void setConnectionParameters(final float interval,
										final int mtu, final int bufferSize,
										final Phy txPhy, final Phy rxPhy) {
		this.currentConnectionInterval = interval;
		this.mtu = mtu;
		this.bufferSize = bufferSize;
		this.txPhy = txPhy;
		this.rxPhy = rxPhy;
	}

	/**
	 * Clears the graph.
	 */
	public void clear() {
		Arrays.fill(progressData, 0);
		currentIndex = 0;
		currentMaxThroughput = maxThroughput;
		invalidate();
	}

	// Helper methods ------------------------------------------------------------------------------

	private void recalculate() {
		float previousX = 0, previousY = 0;

		throughputPath.rewind();
		for (int i = 0; i < currentIndex; ++i) {
			final float progress = progressData[i] / 100.0f;
			final float x = (float) width * progress;
			final float y = height - height * averageThroughputData[i] / currentMaxThroughput;
			if (i == 0) {
				// As there's no previous X coordinate, let's just estimate it.
				// It cannot be 0, as the upload may start from any point when resumed.
				previousX = x - (float) width / 100.0f;
				// There is also no average for older values, so use instantaneous value this time.
				previousY = y;

				throughputPath.moveTo(previousX, height);
				throughputPath.lineTo(previousX, y);
			}
			averageThroughputPoints[4 * i] = previousX;
			averageThroughputPoints[4 * i + 1] = previousY;
			averageThroughputPoints[4 * i + 2] = x;
			averageThroughputPoints[4 * i + 3] = y;

			throughputPath.lineTo(x, y);

			previousX = x;
			previousY = y;
		}
		throughputPath.rLineTo(0, height - previousY);
		throughputPath.close();

		invalidate();
	}

	private void recalculateMetadata() {
		// Recalculate the indicator height.
		tenKbPerSHeight = 10.0f * height / currentMaxThroughput;
		invalidate();
	}

	// Saved State ---------------------------------------------------------------------------------

	static class SavedState extends BaseSavedState {
		private float maxThroughput;
		private float[] averageThroughputData;
		private float[] connectionIntervalData;
		private float currentConnectionInterval;
		private int mtu, bufferSize;
		private Phy txPhy, rxPhy;
		private int[] progressData;
		private int currentPercent;
		private boolean showMetadata;

		/**
		 * Constructor called from {@link ThroughputGraph#onSaveInstanceState()}
		 */
		SavedState(Parcelable superState) {
			super(superState);
		}

		SavedState(Parcel in) {
			super(in);
			maxThroughput = in.readFloat();
			averageThroughputData = in.createFloatArray();
			connectionIntervalData = in.createFloatArray();
			currentConnectionInterval = in.readFloat();
			mtu = in.readInt();
			bufferSize = in.readInt();
			txPhy = (Phy) in.readSerializable();
			rxPhy = (Phy) in.readSerializable();
			progressData = in.createIntArray();
			currentPercent = in.readInt();
			showMetadata = in.readInt() == 1;
		}

		@Override
		public void writeToParcel(Parcel dest, int flags) {
			super.writeToParcel(dest, flags);
			dest.writeFloat(maxThroughput);
			dest.writeFloatArray(averageThroughputData);
			dest.writeFloatArray(connectionIntervalData);
			dest.writeFloat(currentConnectionInterval);
			dest.writeInt(mtu);
			dest.writeInt(bufferSize);
			dest.writeSerializable(txPhy);
			dest.writeSerializable(rxPhy);
			dest.writeIntArray(progressData);
			dest.writeInt(currentPercent);
			dest.writeInt(showMetadata ? 1 : 0);
		}

		public static final Creator<SavedState> CREATOR = new Creator<>() {
			@Override
			public SavedState createFromParcel(Parcel in) {
				return new SavedState(in);
			}

			@Override
			public SavedState[] newArray(int size) {
				return new SavedState[size];
			}
		};
	}
}
