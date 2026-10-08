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

import java.util.ArrayList;
import java.util.List;

import kotlin.enums.EnumEntries;
import no.nordicsemi.android.mcumgr.sample.R;
import no.nordicsemi.kotlin.ble.core.Phy;

@SuppressWarnings("unused")
public class ThroughputGraph extends View {
	private static final float DEFAULT_MAX_THROUGHPUT = 40.0f;

	private final Paint parametersPaint;
	private final Paint averageThroughputPaint;
	private final Paint averageThroughputFillPaint;
	private final Paint previousThroughputPaint;
	private final Paint horizontalLinesPaint;
	private float tenKbPerSHeight;
	/** View dimension. */
	private int width, height;
	private boolean showMetadata;

	/** A single upload attempt. A new series is started when the progress goes back, e.g. after resuming. */
	private static final class Series {
		final float[] averageThroughputData = new float[MAX_POINTS];
		final float[] connectionIntervalData = new float[MAX_POINTS];
		/** Ordinals of the TX and RX PHY at each point, or -1 if unknown. */
		final int[] txPhyData = new int[MAX_POINTS];
		final int[] rxPhyData = new int[MAX_POINTS];
		final int[] progressData = new int[MAX_POINTS];
		final float[] points = new float[4 * MAX_POINTS];
		final Path path = new Path();
		int count;
	}

	private static final int MAX_POINTS = 101;
	/** Number of older series kept, in addition to the current one. */
	private static final int MAX_OLD_SERIES = 4;

	private final List<Series> series = new ArrayList<>();

	private float currentMaxThroughput, maxThroughput, currentConnectionInterval;
	private int mtu, bufferSize;
	@Nullable private Phy txPhy, rxPhy;

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

		previousThroughputPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		previousThroughputPaint.setStrokeWidth(3);
		previousThroughputPaint.setStrokeJoin(Paint.Join.ROUND);
		previousThroughputPaint.setColor(averageThroughputPaint.getColor());
		previousThroughputPaint.setAlpha(80);

		averageThroughputTextWidth = averageThroughputPaint.measureText("XX.X kB/s");

		setOnClickListener(v -> {
			showMetadata = !showMetadata;
			invalidate();
		});
	}

	// State restoration ---------------------------------------------------------------------------

	@Override
	protected void onRestoreInstanceState(final Parcelable state) {
		if (!(state instanceof SavedState ss)) {
			super.onRestoreInstanceState(state);
			return;
		}
		super.onRestoreInstanceState(ss.getSuperState());

		currentMaxThroughput = ss.maxThroughput;
		currentConnectionInterval = ss.currentConnectionInterval;
		mtu = ss.mtu;
		bufferSize = ss.bufferSize;
		txPhy = ss.txPhy;
		rxPhy = ss.rxPhy;
		showMetadata = ss.showMetadata;
		series.clear();
		series.addAll(ss.series);
		recalculate();
	}

	@Nullable
	@Override
	protected Parcelable onSaveInstanceState() {
		final Parcelable superState = super.onSaveInstanceState();
		final SavedState state = new SavedState(superState);
		state.maxThroughput = currentMaxThroughput;
		state.currentConnectionInterval = currentConnectionInterval;
		state.mtu = mtu;
		state.bufferSize = bufferSize;
		state.txPhy = txPhy;
		state.rxPhy = rxPhy;
		state.showMetadata = showMetadata;
		state.series = new ArrayList<>(series);
		return state;
	}

	// Drawing -------------------------------------------------------------------------------------

	@Override
	protected void onDraw(@NotNull final Canvas canvas) {
		super.onDraw(canvas);

		if (series.isEmpty()) {
			return;
		}
		final Series current = series.get(series.size() - 1);

		// First, draw the throughput path of the current series.
		canvas.drawPath(current.path, averageThroughputFillPaint);

		// Draw the horizontal lines indicating each 10 kB/s.
		for (float h = height; h > 0; h -= tenKbPerSHeight) {
			canvas.drawLine(0, h, width, h, horizontalLinesPaint);
		}

		// Draw the previous series as dimmed lines.
		for (int i = 0; i < series.size() - 1; i++) {
			final Series old = series.get(i);
			canvas.drawLines(old.points, 0, old.count << 2, previousThroughputPaint);
		}

		// Draw the average throughput.
		canvas.drawLines(current.points, 0, current.count << 2, averageThroughputPaint);

		// And print the average throughput value.
		final int last = current.count - 1;
		final String text = getResources().getString(R.string.image_upgrade_speed, current.averageThroughputData[last]);
		final float x = current.points[(current.count << 2) - 2] - averageThroughputTextWidth;
		final float y = current.points[(current.count << 2) - 1] - 2 * averageThroughputPaint.getStrokeWidth();
		canvas.drawText(text, Math.max(0, x), Math.max(0, y), averageThroughputPaint);

		// Draw optional metadata: the MTU and SAR at the start, and the connection interval and PHY
		// each time one of them changes.
		if (showMetadata && currentConnectionInterval > 0) {
			float labelsEnd = 0;
			final float padding = 2 * parametersPaint.getStrokeWidth();
			for (int i = 0; i < current.count; ++i) {
				final boolean first = i == 0;
				final boolean intervalChanged = first ||
						current.connectionIntervalData[i] != current.connectionIntervalData[i - 1];
				final boolean phyChanged = first ||
						current.txPhyData[i] != current.txPhyData[i - 1] ||
						current.rxPhyData[i] != current.rxPhyData[i - 1];
				if (!intervalChanged && !phyChanged) {
					continue;
				}
				final float px = current.points[(i << 2)];
				final float py = current.points[(i << 2) + 1];
				canvas.drawLine(px, py, px, height, parametersPaint);

				final List<String> parts = new ArrayList<>();
				if (first) {
					parts.add(bufferSize > mtu ?
							getResources().getString(R.string.image_upgrade_mtu_sar, mtu, bufferSize) :
							getResources().getString(R.string.image_upgrade_mtu, mtu));
				}
				if (phyChanged) {
					parts.add(getResources().getString(R.string.image_upgrade_phy,
							getPhyAsString(current.txPhyData[i], current.rxPhyData[i])));
				}
				if (intervalChanged) {
					parts.add(getResources().getString(R.string.image_upgrade_intv, current.connectionIntervalData[i]));
				}

				float textWidth = 0;
				for (final String part : parts) {
					textWidth = Math.max(textWidth, parametersPaint.measureText(part));
				}
				// A label that would overlap the previous one is skipped; its line is still drawn.
				final float textX = px + padding;
				if (textX >= labelsEnd) {
					float offset = padding;
					// Draw bottom-up, so the lines are in the order they were added, top to bottom.
					for (int j = parts.size() - 1; j >= 0; --j) {
						canvas.drawText(parts.get(j), textX, height - offset, parametersPaint);
						offset += parametersPaint.getTextSize();
					}
					labelsEnd = textX + textWidth + padding;
				}
			}
		}
	}

	private static final EnumEntries<Phy> PHYS = Phy.getEntries();

	@NotNull
	private static String getPhyAsString(final int tx, final int rx) {
		if (tx < 0 && rx < 0) {
			return "?";
		}
		final String txString = tx >= 0 ? String.valueOf(PHYS.get(tx)) : "?";
		final String rxString = rx >= 0 ? String.valueOf(PHYS.get(rx)) : "?";
		return tx == rx ? txString : txString + " / " + rxString;
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
	 * @param maxThroughput the value considered as high. A line will be drawn there.
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
	 * @param progress The current upload percentage for the measured throughput, from 0 to 100.
	 * @param averageThroughput The average throughput in kB/s.
	 * @param newSeries True to start a new series, keeping the previous ones dimmed.
	 */
	public void addProgress(final int progress, final float averageThroughput, final boolean newSeries) {
		if (progress < 0 || progress >= MAX_POINTS) {
			return;
		}
		Series current = series.isEmpty() ? null : series.get(series.size() - 1);
		// A replayed value (e.g. after the view was recreated) must not start a new series.
		if (current != null && current.count > 0
				&& current.progressData[current.count - 1] == progress
				&& current.averageThroughputData[current.count - 1] == averageThroughput) {
			return;
		}
		// When the progress goes back (the upload was restarted or resumed), start a new series.
		if (current == null || newSeries || (current.count > 0 && progress <= current.progressData[current.count - 1])) {
			current = new Series();
			series.add(current);
			while (series.size() > MAX_OLD_SERIES + 1) {
				series.remove(0);
			}
		}
		final int i = current.count++;
		current.averageThroughputData[i] = averageThroughput;
		current.connectionIntervalData[i] = currentConnectionInterval;
		current.txPhyData[i] = txPhy != null ? txPhy.ordinal() : -1;
		current.rxPhyData[i] = rxPhy != null ? rxPhy.ordinal() : -1;
		current.progressData[i] = progress;

		if (currentMaxThroughput < averageThroughput * 1.2f) {
			currentMaxThroughput = averageThroughput * 1.2f;
			recalculateMetadata();
		}
		recalculate();
	}

	/**
	 * Sets the new connection interval.
	 *
	 * @param interval the connection interval, in milliseconds.
	 * @param mtu current MTU.
	 * @param bufferSize maximum McuMgr buffer size.
	 * @param txPhy	current TX PHY used, or null if unknown.
	 * @param rxPhy	current RX PHY used, or null if unknown.
	 */
	public void setConnectionParameters(final float interval,
										final int mtu, final int bufferSize,
										@Nullable final Phy txPhy, @Nullable final Phy rxPhy) {
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
		series.clear();
		currentMaxThroughput = maxThroughput;
		invalidate();
	}

	// Helper methods ------------------------------------------------------------------------------

	private void recalculate() {
		for (final Series item : series) {
			recalculate(item);
		}
		invalidate();
	}

	private void recalculate(final Series item) {
		float previousX = 0, previousY = 0;

		item.path.rewind();
		for (int i = 0; i < item.count; ++i) {
			final float progress = item.progressData[i] / 100.0f;
			final float x = (float) width * progress;
			final float y = height - height * item.averageThroughputData[i] / currentMaxThroughput;
			if (i == 0) {
				// As there's no previous X coordinate, let's just estimate it.
				// It cannot be 0, as the upload may start from any point when resumed.
				previousX = x - (float) width / 100.0f;
				// There is also no average for older values, so use instantaneous value this time.
				previousY = y;

				item.path.moveTo(previousX, height);
				item.path.lineTo(previousX, y);
			}
			item.points[4 * i] = previousX;
			item.points[4 * i + 1] = previousY;
			item.points[4 * i + 2] = x;
			item.points[4 * i + 3] = y;

			item.path.lineTo(x, y);

			previousX = x;
			previousY = y;
		}
		if (item.count > 0) {
			item.path.rLineTo(0, height - previousY);
			item.path.close();
		}
	}

	private void recalculateMetadata() {
		// Recalculate the indicator height.
		tenKbPerSHeight = 10.0f * height / currentMaxThroughput;
		invalidate();
	}

	// Saved State ---------------------------------------------------------------------------------

	static class SavedState extends BaseSavedState {
		private float maxThroughput;
		private float currentConnectionInterval;
		private int mtu, bufferSize;
		@Nullable private Phy txPhy, rxPhy;
		private boolean showMetadata;
		private List<Series> series = new ArrayList<>();

		/**
		 * Constructor called from {@link ThroughputGraph#onSaveInstanceState()}
		 */
		SavedState(Parcelable superState) {
			super(superState);
		}

		SavedState(Parcel in) {
			super(in);
			maxThroughput = in.readFloat();
			currentConnectionInterval = in.readFloat();
			mtu = in.readInt();
			bufferSize = in.readInt();
			txPhy = (Phy) in.readSerializable();
			rxPhy = (Phy) in.readSerializable();
			showMetadata = in.readInt() == 1;
			final int size = in.readInt();
			for (int i = 0; i < size; i++) {
				final Series item = new Series();
				item.count = in.readInt();
				in.readFloatArray(item.averageThroughputData);
				in.readFloatArray(item.connectionIntervalData);
				in.readIntArray(item.txPhyData);
				in.readIntArray(item.rxPhyData);
				in.readIntArray(item.progressData);
				series.add(item);
			}
		}

		@Override
		public void writeToParcel(Parcel dest, int flags) {
			super.writeToParcel(dest, flags);
			dest.writeFloat(maxThroughput);
			dest.writeFloat(currentConnectionInterval);
			dest.writeInt(mtu);
			dest.writeInt(bufferSize);
			dest.writeSerializable(txPhy);
			dest.writeSerializable(rxPhy);
			dest.writeInt(showMetadata ? 1 : 0);
			dest.writeInt(series.size());
			for (final Series item : series) {
				dest.writeInt(item.count);
				dest.writeFloatArray(item.averageThroughputData);
				dest.writeFloatArray(item.connectionIntervalData);
				dest.writeIntArray(item.txPhyData);
				dest.writeIntArray(item.rxPhyData);
				dest.writeIntArray(item.progressData);
			}
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
