package com.eottabom.migration.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 임시 파일에 다 쓴 뒤 바꿔치기한다. 쓰는 중에 러너가 죽어도 재개 정보가 반쯤 쓰여 깨지지 않는다. */
public final class AtomicFiles {

	private AtomicFiles() {
	}

	public static void write(Path file, String content) {
		try {
			Path parent = file.toAbsolutePath().getParent();
			Files.createDirectories(parent);
			Path temp = Files.createTempFile(parent, "." + file.getFileName(), ".tmp");
			try {
				Files.writeString(temp, content);
				move(temp, file);
			}
			finally {
				Files.deleteIfExists(temp);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void move(Path temp, Path file) throws IOException {
		try {
			Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException ex) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

}
