package com.eottabom.migration.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.eottabom.migration.misc.AtomicFiles;
import com.eottabom.migration.misc.TextFiles;
import org.jspecify.annotations.Nullable;

/**
 * 대상 프로젝트의 .spring-boot-migrator. clean 에 지워지지 않게 build 밖에 두고 git 에서는 뺀다.
 *
 * <pre>
 * .spring-boot-migrator/
 *   result.html          전 stage 를 한 페이지로 보는 결과
 *   history.md           실행마다 시작, 목표, stage 별 결과 한 줄
 *   run-state.json       재개 기록 (끝까지 마치면 지운다)
 *   start/               시작할 때 모은 의존성 버전, detect 결과, 원본 빌드
 *   scan/                migrationScan 결과
 *   03-boot-3.4/         stage 별 결과, patch, 로그 ({@link StageFiles})
 * </pre>
 */
public record MigrationWorkspace(Path dir) {

	public static final String DIR_NAME = ".spring-boot-migrator";

	private static final Pattern STAGE_DIR = Pattern.compile("^\\d{2,}-.+$");

	public MigrationWorkspace {
		try {
			Files.createDirectories(dir);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public static MigrationWorkspace in(Path projectDir) {
		return new MigrationWorkspace(projectDir.resolve(DIR_NAME));
	}

	/** 이 디렉토리를 둔 대상 프로젝트 */
	public Path projectDir() {
		return Objects.requireNonNull(this.dir.getParent());
	}

	public Path resultHtml() {
		return this.dir.resolve("result.html");
	}

	public Path history() {
		return this.dir.resolve("history.md");
	}

	public Path runState() {
		return this.dir.resolve("run-state.json");
	}

	public Path lock() {
		return this.dir.resolve(".lock");
	}

	/** git write-tree 가 쓰는 임시 index */
	public Path tempIndex() {
		return this.dir.resolve(".index-tmp");
	}

	/** 시작할 때 모은 파일 */
	public ProjectFiles start() {
		return new ProjectFiles(this.dir.resolve("start"));
	}

	/** migrationScan 이 모은 파일 */
	public ProjectFiles scan() {
		return new ProjectFiles(this.dir.resolve("scan"));
	}

	public StageFiles stage(String tag) {
		return new StageFiles(this.dir.resolve(tag));
	}

	/** 지난 실행까지 포함한 stage 폴더 이름 (번호 순서) */
	public List<String> stageTags() {
		try (Stream<Path> files = Files.list(this.dir)) {
			return files.filter((file) -> Files.isDirectory(file))
				.map((file) -> file.getFileName().toString())
				.filter((name) -> STAGE_DIR.matcher(name).matches())
				.sorted(Comparator.comparingInt((String tag) -> Integer.parseInt(tag.substring(0, tag.indexOf('-')))))
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** 같은 태그로 다시 시도할 때 지난 시도의 결과가 섞이지 않게 stage 폴더를 비운다 */
	public void clearStage(String tag) {
		deleteTree(this.dir.resolve(tag));
	}

	/** tag stage 가 남긴 의존성 버전 목록. tag 가 없거나 파일이 없으면 시작할 때 모은 목록 */
	public Path versionsAfter(@Nullable String tag) {
		Path stageVersions = (tag != null) ? stage(tag).versions() : null;
		return (stageVersions != null && Files.exists(stageVersions)) ? stageVersions : start().versions();
	}

	public void appendHistory(String projectName, String text) {
		Path file = history();
		String previous = Files.exists(file) ? TextFiles.read(file) : "# " + projectName + " 마이그레이션 기록\n\n";
		AtomicFiles.write(file, previous + text);
	}

	/** from 이 없으면 빈 파일을 만들고 false. */
	public static boolean copyOrEmpty(Path from, Path to) {
		try {
			Files.createDirectories(Objects.requireNonNull(to.getParent()));
			if (Files.exists(from)) {
				Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
				return true;
			}
			writeEmpty(to);
			return false;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public static void writeEmpty(Path file) {
		AtomicFiles.write(file, "");
	}

	static void deleteTree(Path path) {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (Stream<Path> files = Files.walk(path)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(file);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
