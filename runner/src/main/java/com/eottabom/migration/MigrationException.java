package com.eottabom.migration;

import org.jspecify.annotations.Nullable;

/**
 * 사용자에게 그대로 보여 줄 실패. 메시지에 무엇이 틀렸고 어떻게 고치는지를 담는다. 태스크(plugin)가 한 곳에서 Gradle 의 실패로 바꾼다. 러너
 * 코드의 결함은 이 예외가 아니라 IllegalStateException 같은 표준 예외로 둔다.
 */
public class MigrationException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public MigrationException(String message) {
		super(message);
	}

	public MigrationException(String message, @Nullable Throwable cause) {
		super(message, cause);
	}

}
