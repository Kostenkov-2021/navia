package com.wikiponder.touchmap;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wikiponder.touchmap.classes.vpr.VprAlign;
import com.wikiponder.touchmap.classes.vpr.VprModel;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;

/**
 * Runs VprAlign on the place photos already saved on the device (files/visual_places/&lt;id&gt;/)
 * and logs the turn between every pair, tag VprAlign, for comparison with the same computation in
 * Python. Skipped when no photos are saved.
 *
 *   ./gradlew -PtestRelease assembleRelease assembleReleaseAndroidTest, adb install -r both, then
 *   adb shell am instrument -w -e class com.wikiponder.touchmap.VprAlignDeviceTest \
 *       com.wikiponder.touchmap.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4.class)
public class VprAlignDeviceTest {
	@Test
	public void turnsBetweenSavedFrames() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		File[] places = new File(context.getFilesDir(), "visual_places").listFiles();
		Assume.assumeTrue(places != null && places.length > 0);
		VprModel model = VprModel.get(context);
		Assume.assumeTrue(model != null);
		for (File place : places) {
			File[] jpgs = place.listFiles((d, n) -> n.endsWith(".jpg"));
			if (jpgs == null || jpgs.length < 2) continue;
			Arrays.sort(jpgs);
			VprModel.Embedding[] e = new VprModel.Embedding[jpgs.length];
			for (int i = 0; i < jpgs.length; i++) {
				Bitmap b = BitmapFactory.decodeFile(jpgs[i].getAbsolutePath());
				e[i] = model.embedFull(b);
				b.recycle();
			}
			for (int live = 0; live < e.length; live++) {
				for (int saved = 0; saved < e.length; saved++) {
					if (live == saved) continue;
					long t = System.nanoTime();
					float turn = VprAlign.turnDegrees(e[live], e[saved].patches, 54f);
					long ms = (System.nanoTime() - t) / 1_000_000;
					Log.i("VprAlign", String.format(java.util.Locale.US, "%s %s sim=%.2f turn=%.1f %dms",
							jpgs[live].getName(), jpgs[saved].getName(),
							VprModel.similarity(e[live].global, e[saved].global), turn, ms));
				}
			}
		}
		VprModel.release();
	}
}
