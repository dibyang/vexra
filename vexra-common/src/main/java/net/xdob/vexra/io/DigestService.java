package net.xdob.vexra.io;

import java.io.File;
import java.io.IOException;

public interface DigestService<T extends Digest> {
	T computeDigestForFile(File dataFile) throws IOException;
	T computeAndSaveDigestForFile(File dataFile);
	T readStoredDigestForFile(File dataFile) throws IOException;
	void saveDigestFile(File dataFile, T digest) throws IOException;
}
