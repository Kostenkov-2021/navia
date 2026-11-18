package com.wikiponder.touchmap.classes.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wikiponder.touchmap.classes.boxes.BoxManifest;
import com.wikiponder.touchmap.classes.boxes.BoxManifestRepository;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;

/** Downloads real (small) boxes over the network, directly and through the wikiponder.com fallback
 * that is used when Backblaze can't be reached. Only downloads, never touches the app's map db. */
@RunWith(AndroidJUnit4.class)
public class BoxDownloadFallbackDeviceTest {
	private final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
	private final File dest = new File(ctx.getCacheDir(), "fallback_test_box.db.gz");

	@After
	public void tearDown() {
		BoxImporter.directUrlForTest = null;
		dest.delete();
	}

	@Test
	public void directDownloadWorks() throws Exception {
		BoxImporter importer = new BoxImporter(ctx, null);
		BoxManifest.Entry box = entry(2130);
		importer.downloadBox(box, dest, 1, 1, 0, 1);
		assertFalse("direct download should not need the fallback", importer.directFailed);
		assertGzipOfSize(box);
	}

	@Test
	public void unreachableDirectHostUsesFallback() throws Exception {
		BoxImporter.directUrlForTest = "https://blocked.backblazeb2.invalid/box.db.gz";
		BoxImporter importer = new BoxImporter(ctx, null);
		// 2136 may be cold on the server (202 then wait), 2376 was cached during development (302).
		for (int index : new int[]{2136, 2376}) {
			BoxManifest.Entry box = entry(index);
			importer.downloadBox(box, dest, 1, 1, 0, 1);
			assertTrue(importer.directFailed);
			assertGzipOfSize(box);
		}
	}

	private BoxManifest.Entry entry(int index) throws Exception {
		for (BoxManifest.Entry e : BoxManifestRepository.getOrFetch(ctx).boxes) if (e.index == index) return e;
		throw new AssertionError("box " + index + " not in manifest");
	}

	private void assertGzipOfSize(BoxManifest.Entry box) throws Exception {
		byte[] magic = new byte[2];
		try (FileInputStream in = new FileInputStream(dest)) {
			assertEquals(2, in.read(magic));
		}
		assertEquals(0x1f, magic[0] & 0xff);
		assertEquals(0x8b, magic[1] & 0xff);
		double mb = dest.length() / 1e6;
		assertEquals("box " + box.index + " size", box.gzSizeMb, mb, 0.01);
	}
}
