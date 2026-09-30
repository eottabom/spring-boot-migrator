package com.eottabom.migration.version;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * 대상 프로젝트에서 resolve 된 의존성 버전. verify.init.gradle 의 migrationResolvedVersions 가 남긴 파일을 한 번
 * 읽어 값으로 넘긴다.
 *
 * @param byModule group:artifact → version
 */
public record ResolvedVersions(Map<String, String> byModule) {

	/** 버전을 모은 적이 없거나 모으지 못했다 */
	public static final ResolvedVersions UNKNOWN = new ResolvedVersions(Map.of());

	/** 한 줄에 group:artifact=version. 파일이 없으면 {@link #UNKNOWN} */
	public static ResolvedVersions read(Path file) {
		if (!Files.exists(file)) {
			return UNKNOWN;
		}
		Map<String, String> byModule = new LinkedHashMap<>();
		try {
			for (String line : Files.readAllLines(file)) {
				int separator = line.indexOf('=');
				if (separator > 0) {
					byModule.put(line.substring(0, separator), line.substring(separator + 1));
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return new ResolvedVersions(byModule);
	}

	public @Nullable String of(String module) {
		return this.byModule.get(module);
	}

	public Set<String> modules() {
		return this.byModule.keySet();
	}

	public boolean isUnknown() {
		return this.byModule.isEmpty();
	}

}
