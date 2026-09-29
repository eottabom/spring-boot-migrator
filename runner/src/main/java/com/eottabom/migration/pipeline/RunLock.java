package com.eottabom.migration.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;

import com.eottabom.migration.workspace.MigrationWorkspace;
import org.gradle.api.GradleException;
import org.jspecify.annotations.Nullable;

/**
 * 같은 프로젝트에 migrationRun 이 동시에 돌지 않게 막는다. 임시 index, history.md, run-state.json 을 함께 쓰기
 * 때문이다. 프로세스가 죽으면 OS 가 잠금을 푼다.
 */
final class RunLock implements AutoCloseable {

	private final FileChannel channel;

	private final FileLock lock;

	private RunLock(FileChannel channel, FileLock lock) {
		this.channel = channel;
		this.lock = lock;
	}

	static RunLock acquire(MigrationWorkspace ws) {
		FileChannel channel = null;
		try {
			channel = FileChannel.open(ws.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			FileLock lock = tryLock(channel);
			if (lock == null) {
				channel.close();
				throw new GradleException("같은 프로젝트에서 migrationRun 이 이미 실행 중이에요 (" + ws.lock() + ")");
			}
			return new RunLock(channel, lock);
		}
		catch (IOException ex) {
			closeQuietly(channel);
			throw new UncheckedIOException(ex);
		}
	}

	private static @Nullable FileLock tryLock(FileChannel channel) throws IOException {
		try {
			return channel.tryLock();
		}
		catch (OverlappingFileLockException ex) {
			// 같은 JVM(같은 Gradle 데몬)에서 이미 잡았다
			return null;
		}
	}

	private static void closeQuietly(@Nullable FileChannel channel) {
		if (channel == null) {
			return;
		}
		try {
			channel.close();
		}
		catch (IOException ignored) {
		}
	}

	/** 잠금을 풀지 못해도 채널은 닫는다 (채널을 닫으면 OS 가 잠금도 푼다) */
	@Override
	public void close() {
		IOException failure = null;
		try {
			this.lock.release();
		}
		catch (IOException ex) {
			failure = ex;
		}
		try {
			this.channel.close();
		}
		catch (IOException ex) {
			if (failure == null) {
				failure = ex;
			}
			else {
				failure.addSuppressed(ex);
			}
		}
		if (failure != null) {
			throw new UncheckedIOException(failure);
		}
	}

}
